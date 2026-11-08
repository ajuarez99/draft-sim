package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalProperties;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.KaggleIdentity.Index;
import com.ballknowers.draftsim.historical.KaggleIdentity.Issue;
import com.ballknowers.draftsim.historical.KaggleIdentity.PersonSeason;
import com.ballknowers.draftsim.historical.KaggleIdentity.SeasonFileRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Kaggle half of the historical import (spec 053 R1-R5, F3, F11, F14): seasons 1969..2016 of box scores.
 *
 * <p>Phase 1 ({@link #prepare}) downloads the pinned zip, verifies its SHA-256 (whatever the URL), streams the
 * box-score entry ONCE into the UNLOGGED staging table through pgjdbc's {@code CopyManager} (the file is
 * player-sorted, so it cannot be grouped by season in one pass, and prod runs at -Xmx1024m), and reads the
 * small season file into memory. Phase 2 ({@link #importSeason}) works one season at a time from staging in its
 * own transaction. A season with a problem is reported and NOT committed; the others carry on.
 */
@Component
public class KaggleHistoricalSource {

    private static final Logger log = LoggerFactory.getLogger(KaggleHistoricalSource.class);

    static final String BOX_ENTRY = "NBA Player Box Score Stats(1950 - 2022).csv";
    static final String SEASON_ENTRY = "NBA Player Stats(1950 - 2022).csv";

    /** Season starts imported from Kaggle; 1969 is card lines only and is never offered (F14). */
    public static final int FIRST_SEASON = 1969, LAST_SEASON = 2016;

    private static final String COPY_SQL = "COPY historical_kaggle_staging (season_start, game_id, game_date, team, "
            + "player_name, min, pts, reb, ast, stl, blk, tov, fg3m, fgm, fga, ftm, fta) FROM STDIN WITH (FORMAT csv)";

    /** The seven box-score stats the era rule is about, in the keys the scoring map uses. */
    private static final List<String> ERA_STATS = List.of("pts", "reb", "ast", "stl", "blk", "to", "tpm");

    private final HistoricalProperties props;
    private final HistoricalDataRepository data;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public KaggleHistoricalSource(HistoricalProperties props, HistoricalDataRepository data, JdbcTemplate jdbc,
                                  DataSource dataSource, TransactionTemplate tx, ObjectMapper json) {
        this.props = props;
        this.data = data;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = tx;
        this.json = json;
    }

    /** What phase 1 leaves for phase 2. {@link #close} deletes the temp zip. */
    public static final class Prepared implements AutoCloseable {
        final Path zip;
        final Index index;
        final long stagedRows;

        Prepared(Path zip, Index index, long stagedRows) {
            this.zip = zip;
            this.index = index;
            this.stagedRows = stagedRows;
        }

        public Index index() {
            return index;
        }

        public long stagedRows() {
            return stagedRows;
        }

        @Override
        public void close() {
            try {
                if (zip != null) Files.deleteIfExists(zip);
            } catch (IOException e) {
                log.warn("could not delete temp zip {}: {}", zip, e.toString());
            }
        }
    }

    // ---- phase 1 -------------------------------------------------------------------------------------

    public Prepared prepare(ImportReport report) throws IOException, SQLException {
        long t0 = System.currentTimeMillis();
        Path zip = Files.createTempFile("historical-kaggle-", ".zip");
        try {
            download(props.kaggleZipUrl(), zip);
            String sha = sha256(zip);
            if (!sha.equalsIgnoreCase(props.kaggleZipSha256())) {
                throw new IllegalStateException("Kaggle zip SHA-256 mismatch: expected " + props.kaggleZipSha256()
                        + " but the download is " + sha + ". Refusing to import an unpinned file.");
            }
            log.info("historical import: zip verified ({} bytes, sha256 ok) in {} ms", Files.size(zip),
                    System.currentTimeMillis() - t0);
            report.note("zipSha256", sha);

            data.truncateStaging();
            long staged;
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                staged = stageBoxScores(zf);
            }
            report.note("stagedBoxRows", staged);
            log.info("historical import: {} box rows staged in {} ms", staged, System.currentTimeMillis() - t0);

            List<SeasonFileRow> rows;
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                ZipEntry e = requireEntry(zf, SEASON_ENTRY);
                try (InputStream in = zf.getInputStream(e)) {
                    rows = KaggleIdentity.readSeasonFile(new InputStreamReader(in, StandardCharsets.UTF_8),
                            FIRST_SEASON + 1, LAST_SEASON + 1);
                }
            }
            Index index = new Index(rows, KaggleIdentity.Curation.load());
            report.note("seasonFileRows", rows.size());
            return new Prepared(zip, index, staged);
        } catch (IOException | SQLException | RuntimeException e) {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException ignored) {
                // the original failure is the one to surface
            }
            throw e;
        }
    }

    private void download(String url, Path target) throws IOException {
        URI uri = URI.create(url);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Files.copy(Path.of(uri), target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        try {
            HttpResponse<Path> resp = client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(target));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Kaggle download returned HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the Kaggle zip", e);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ZipEntry requireEntry(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) throw new IllegalStateException("the Kaggle zip has no entry '" + name + "'");
        return e;
    }

    /** Streams the box CSV once through COPY. Never holds the file; keeps seasons 1969..2016 regular season. */
    private long stageBoxScores(ZipFile zf) throws IOException, SQLException {
        ZipEntry entry = requireEntry(zf, BOX_ENTRY);
        try (Connection con = dataSource.getConnection();
             InputStream in = zf.getInputStream(entry);
             KaggleCsv csv = new KaggleCsv(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            CopyManager cm = con.unwrap(PGConnection.class).getCopyAPI();
            int cGame = col(csv, "Game_ID"), cDate = col(csv, "GAME_DATE"), cTeam = col(csv, "Team"),
                    cName = col(csv, "PLAYER_NAME"), cMin = col(csv, "MIN"), cPts = col(csv, "PTS"),
                    cReb = col(csv, "REB"), cAst = col(csv, "AST"), cStl = col(csv, "STL"),
                    cBlk = col(csv, "BLK"), cTov = col(csv, "TOV"), cFg3m = col(csv, "FG3M"),
                    cFgm = col(csv, "FGM"), cFga = col(csv, "FGA"), cFtm = col(csv, "FTM"), cFta = col(csv, "FTA");
            long rows = 0;
            CopyIn copy = cm.copyIn(COPY_SQL);
            try {
                StringBuilder sb = new StringBuilder(1 << 16);
                String[] r;
                while ((r = csv.next()) != null) {
                    String gameId = r[cGame];
                    if (gameId == null || !gameId.startsWith("002")) continue; // regular season only
                    int season = KaggleCsv.seasonStartOfGameId(gameId);
                    if (season < FIRST_SEASON || season > LAST_SEASON) continue;
                    sb.append(season).append(',');
                    csvField(sb, gameId).append(',');
                    csvField(sb, r[cDate]).append(',');
                    csvField(sb, r[cTeam]).append(',');
                    csvField(sb, r[cName]).append(',');
                    csvField(sb, r[cMin]).append(',');
                    csvField(sb, r[cPts]).append(',');
                    csvField(sb, r[cReb]).append(',');
                    csvField(sb, r[cAst]).append(',');
                    csvField(sb, r[cStl]).append(',');
                    csvField(sb, r[cBlk]).append(',');
                    csvField(sb, r[cTov]).append(',');
                    csvField(sb, r[cFg3m]).append(',');
                    csvField(sb, r[cFgm]).append(',');
                    csvField(sb, r[cFga]).append(',');
                    csvField(sb, r[cFtm]).append(',');
                    csvField(sb, r[cFta]).append('\n');
                    rows++;
                    if (sb.length() > (1 << 15)) {
                        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                        copy.writeToCopy(bytes, 0, bytes.length);
                        sb.setLength(0);
                    }
                }
                if (!sb.isEmpty()) {
                    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                    copy.writeToCopy(bytes, 0, bytes.length);
                }
                copy.endCopy();
            } finally {
                if (copy.isActive()) copy.cancelCopy();
            }
            return rows;
        }
    }

    private static int col(KaggleCsv csv, String name) {
        int i = csv.indexOf(name);
        if (i < 0) throw new IllegalStateException("box-score CSV has no column " + name + ": " + csv.header());
        return i;
    }

    /** CSV field: null is an unquoted empty cell (COPY reads it as NULL), anything else is always quoted. */
    static StringBuilder csvField(StringBuilder sb, String v) {
        if (v == null) return sb;
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"') sb.append('"');
            sb.append(c);
        }
        return sb.append('"');
    }

    // ---- phase 2 -------------------------------------------------------------------------------------

    /** One played-or-not box-score row for one season, as staged. */
    record BoxRow(String gameId, String dateText, String team, String name, Integer min, Integer pts, Integer reb,
                  Integer ast, Integer stl, Integer blk, Integer to, Integer tpm, Integer fgm, Integer fga,
                  Integer ftm, Integer fta) {
        boolean played() {
            return (min != null && min > 0) || (pts != null && pts > 0);
        }

        Integer stat(String key) {
            return switch (key) {
                case "pts" -> pts;
                case "reb" -> reb;
                case "ast" -> ast;
                case "stl" -> stl;
                case "blk" -> blk;
                case "to" -> to;
                case "tpm" -> tpm;
                default -> throw new IllegalArgumentException(key);
            };
        }
    }

    private List<BoxRow> stagedRows(int season) {
        return jdbc.query("""
                select game_id, game_date, team, player_name, min, pts, reb, ast, stl, blk, tov, fg3m, fgm, fga, ftm, fta
                from historical_kaggle_staging where season_start = ?
                """, (rs, i) -> new BoxRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                num(rs.getString(5)), num(rs.getString(6)), num(rs.getString(7)), num(rs.getString(8)),
                num(rs.getString(9)), num(rs.getString(10)), num(rs.getString(11)), num(rs.getString(12)),
                num(rs.getString(13)), num(rs.getString(14)), num(rs.getString(15)), num(rs.getString(16))), season);
    }

    private static Integer num(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Imports one season start. Returns true when it committed. Anything that should stop a season (F2, F5, R5)
     * goes in the report and the season is left untouched.
     */
    public boolean importSeason(int s, Prepared prepared, ImportReport report) {
        Index idx = prepared.index();
        int end = s + 1;
        List<String> reasons = new ArrayList<>();
        try {
            List<PersonSeason> persons = idx.personSeasons(end);
            if (persons.isEmpty()) {
                report.failed(s, "KAGGLE", List.of("no season-file rows for " + s));
                return false;
            }
            for (Issue issue : idx.issuesFor(s)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seasonStart", s);
                m.put("kind", issue.kind());
                m.put("detail", issue.detail());
                report.collision(m);
                reasons.add(issue.kind() + ": " + issue.detail());
            }
            for (PersonSeason p : persons) {
                if (KaggleIdentity.positions(p.chosen().pos()).isEmpty()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seasonStart", s);
                    m.put("name", p.displayName());
                    m.put("pos", p.chosen().pos());
                    report.emptyPosition(m);
                    reasons.add("empty positions: " + p.displayName() + " (Pos '" + p.chosen().pos() + "')");
                }
            }

            if (s == FIRST_SEASON) { // card lines only: not offered, no box scores read
                if (!reasons.isEmpty()) {
                    report.failed(s, "KAGGLE", reasons);
                    return false;
                }
                tx.executeWithoutResult(st -> writeLines(s, persons, new HashMap<>()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seasonStart", s);
                m.put("source", "KAGGLE");
                m.put("linesOnly", true);
                m.put("playerSeasons", persons.size());
                report.season(m);
                return true;
            }

            List<BoxRow> rows = stagedRows(s);
            if (rows.isEmpty()) {
                report.failed(s, "KAGGLE", List.of("no staged box-score rows for " + s));
                return false;
            }
            Set<String> gameIds = new HashSet<>();
            List<LocalDate> allDates = new ArrayList<>();
            Set<String> seenDates = new HashSet<>();
            for (BoxRow r : rows) {
                gameIds.add(r.gameId());
                if (seenDates.add(r.dateText())) allDates.add(KaggleCsv.parseDate(r.dateText()));
            }
            List<BoxRow> played = rows.stream().filter(BoxRow::played).toList();

            // era: coverage over PLAYED rows, tracked from the season file (FR-007)
            Map<String, Double> coverage = new LinkedHashMap<>();
            for (String stat : ERA_STATS) {
                long have = played.stream().filter(r -> r.stat(stat) != null).count();
                coverage.put(stat, played.isEmpty() ? 0.0 : (double) have / played.size());
            }
            Set<String> tracked = trackedStats(idx.rowsForEnd(end));
            EraScoring.Era era = EraScoring.resolve(coverage, tracked, EraScoring.KAGGLE, props.scoring(),
                    props.eraThreshold());

            // identity of every played box row
            Map<String, Index.Match> matchCache = new HashMap<>();
            Map<String, Double> pointsByKey = new HashMap<>();
            Map<String, Integer> gamesByKey = new HashMap<>();
            Map<String, double[]> unmatchedPoints = new LinkedHashMap<>(); // norm name -> {points, games}
            Map<String, String> unmatchedSample = new HashMap<>();
            Map<String, String> unmatchedProblem = new HashMap<>();
            Map<String, Set<String>> unmatchedTeams = new HashMap<>();
            Map<BoxRow, String> keyOfRow = new java.util.IdentityHashMap<>();
            Map<String, Integer> mismatchDropped = new LinkedHashMap<>(); // box rows on a team the season file never lists
            Map<String, Integer> ambiguousDropped = new LinkedHashMap<>(); // "name TEAM" -> played rows left off the board
            for (BoxRow r : played) {
                Index.Match m = matchCache.computeIfAbsent(r.name() + "|" + r.team(),
                        k -> idx.match(end, r.name(), r.team()));
                double pts = countingPoints(r, era.scoring());
                if (KaggleIdentity.AMBIGUOUS_SAME_TEAM.equals(m.problem())) {
                    ambiguousDropped.merge(r.name() + " " + r.team(), 1, Integer::sum);
                } else if (KaggleIdentity.TEAM_MISMATCH.equals(m.problem())) {
                    mismatchDropped.merge(r.name() + " " + r.team() + " (season file: " + m.seasonTeams() + ")", 1,
                            Integer::sum);
                    report.teamMismatch(r.team(), m.seasonTeams(), 1);
                } else if (m.personKey() == null) {
                    String norm = KaggleIdentity.normalize(r.name());
                    double[] acc = unmatchedPoints.computeIfAbsent(norm, k -> new double[2]);
                    acc[0] += pts;
                    acc[1] += 1;
                    unmatchedSample.putIfAbsent(norm, r.name());
                    unmatchedProblem.putIfAbsent(norm, m.problem());
                    unmatchedTeams.computeIfAbsent(norm, k -> new HashSet<>()).add(r.team());
                } else {
                    keyOfRow.put(r, m.personKey());
                    pointsByKey.merge(m.personKey(), pts, Double::sum);
                    gamesByKey.merge(m.personKey(), 1, Integer::sum);
                }
            }

            // fantasy-relevant: the top teams x rounds x 1.5 groups by counting-stat points (R5)
            int relevantN = (int) Math.round(props.teams() * props.rounds() * 1.5);
            List<Map.Entry<String, Double>> ranked = new ArrayList<>();
            for (Map.Entry<String, Double> e : pointsByKey.entrySet()) ranked.add(Map.entry("K:" + e.getKey(), e.getValue()));
            for (Map.Entry<String, double[]> e : unmatchedPoints.entrySet()) ranked.add(Map.entry("U:" + e.getKey(), e.getValue()[0]));
            ranked.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey));
            Set<String> relevant = new HashSet<>();
            for (int i = 0; i < Math.min(relevantN, ranked.size()); i++) relevant.add(ranked.get(i).getKey());

            int skippedUnmatched = 0, skippedUnmatchedRows = 0;
            for (Map.Entry<String, double[]> e : unmatchedPoints.entrySet()) {
                if (relevant.contains("U:" + e.getKey())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seasonStart", s);
                    m.put("name", unmatchedSample.get(e.getKey()));
                    m.put("problem", unmatchedProblem.get(e.getKey()));
                    m.put("countingFantasyPoints", Math.round(e.getValue()[0] * 10) / 10.0);
                    m.put("playedGames", (int) e.getValue()[1]);
                    m.put("boxTeams", new ArrayList<>(new java.util.TreeSet<>(unmatchedTeams.get(e.getKey()))));
                    report.unmatched(m);
                    reasons.add("unmatched fantasy-relevant: " + unmatchedSample.get(e.getKey()));
                } else {
                    skippedUnmatched++;
                    skippedUnmatchedRows += (int) e.getValue()[1];
                }
            }

            // informational: box games above the season file's G, and relevant players with no box rows
            List<String> gameCountDisagreements = new ArrayList<>();
            List<String> relevantWithoutBox = new ArrayList<>();
            List<Map.Entry<String, Double>> seasonRanked = new ArrayList<>();
            Map<String, PersonSeason> personByKey = new HashMap<>();
            for (PersonSeason p : persons) {
                personByKey.put(p.personKey(), p);
                seasonRanked.add(Map.entry(p.personKey(), totalsPoints(p.chosen(), era.scoring())));
                Integer boxGames = gamesByKey.get(p.personKey());
                Integer g = p.chosen().games();
                if (boxGames != null && g != null && boxGames > g + 2) {
                    gameCountDisagreements.add(p.displayName() + " box " + boxGames + " > G " + g);
                }
            }
            seasonRanked.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey));
            for (int i = 0; i < Math.min(relevantN, seasonRanked.size()); i++) {
                String key = seasonRanked.get(i).getKey();
                if (!gamesByKey.containsKey(key) && seasonRanked.get(i).getValue() > 0) {
                    relevantWithoutBox.add(personByKey.get(key).displayName());
                }
            }

            if (!reasons.isEmpty()) {
                report.failed(s, "KAGGLE", reasons);
                return false;
            }

            LocalDate opening = allDates.stream().min(LocalDate::compareTo).orElseThrow();
            LocalDate last = allDates.stream().max(LocalDate::compareTo).orElseThrow();
            HistoricalWeeks weeks = HistoricalWeeks.of(allDates, opening, last);
            int[] gameRowCount = new int[1];
            int[] duplicates = new int[1];
            tx.executeWithoutResult(st -> {
                Map<String, Long> idByKey = new HashMap<>();
                writeLines(s, persons, idByKey);
                List<GameLine> games = new ArrayList<>(played.size());
                Set<String> seen = new HashSet<>();
                for (BoxRow r : played) {
                    String key = keyOfRow.get(r);
                    if (key == null) continue; // unmatched and not fantasy-relevant: left off the board
                    long playerId = idByKey.get(key);
                    long gameId = Long.parseLong(r.gameId().trim());
                    if (!seen.add(playerId + ":" + gameId)) {
                        duplicates[0]++;
                        continue;
                    }
                    LocalDate date = KaggleCsv.parseDate(r.dateText());
                    var wk = weeks.weekOf(date);
                    if (wk.isEmpty()) continue;
                    var d = KaggleIdentity.derive(r.pts(), r.reb(), r.ast(), r.stl(), r.blk());
                    games.add(new GameLine(playerId, gameId, s, date, wk.getAsInt(), r.team(), r.pts(), r.reb(),
                            r.ast(), r.stl(), r.blk(), r.to(), r.tpm(), r.fgm(), r.fga(), r.ftm(), r.fta(), null,
                            null, d.dd(), d.td(), d.bonusPt40(), d.bonusPt50(), d.bonusReb20(), d.bonusAst15()));
                }
                data.replaceSeasonGames(s, games);
                gameRowCount[0] = games.size();
                data.upsertSeason(new SeasonRow("NBA", s, label(s), EraScoring.KAGGLE, opening, last, weeks.weekCount(),
                        gameIds.size(), new ArrayList<>(era.scoring().keySet()), toJson(era.excluded()),
                        toJson(era.coverage()), "PRIOR_SEASON_POINTS", OffsetDateTime.now(ZoneOffset.UTC), null, 0, null));
            });

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seasonStart", s);
            m.put("source", "KAGGLE");
            m.put("games", gameIds.size());
            m.put("playerSeasons", persons.size());
            m.put("gameRows", gameRowCount[0]);
            m.put("duplicateRowsDropped", duplicates[0]);
            m.put("weeks", weeks.weekCount());
            m.put("openingNight", opening.toString());
            m.put("lastGameDate", last.toString());
            m.put("countedStats", new ArrayList<>(era.scoring().keySet()));
            m.put("excludedStats", era.excluded());
            m.put("coverage", era.coverage());
            m.put("trackedStats", new ArrayList<>(new java.util.TreeSet<>(tracked)));
            m.put("skippedUnmatchedPlayerSeasons", skippedUnmatched);
            m.put("skippedUnmatchedRows", skippedUnmatchedRows);
            m.put("boxGamesAboveSeasonFileG", gameCountDisagreements);
            m.put("relevantInSeasonFileWithoutBoxRows", relevantWithoutBox);
            m.put("ambiguousSameTeamRowsDropped", ambiguousDropped);
            m.put("teamMismatchRowsDropped", mismatchDropped);
            report.season(m);
            return true;
        } catch (RuntimeException e) {
            log.warn("historical import: season {} failed: {}", s, e.toString(), e);
            report.failed(s, "KAGGLE", List.of("exception: " + e));
            return false;
        }
    }

    private void writeLines(int s, List<PersonSeason> persons, Map<String, Long> idByKey) {
        List<SeasonLine> lines = new ArrayList<>(persons.size());
        for (PersonSeason p : persons) {
            long id = data.upsertPlayer(EraScoring.KAGGLE, p.personKey(), p.displayName());
            idByKey.put(p.personKey(), id);
            SeasonFileRow c = p.chosen();
            lines.add(new SeasonLine(id, s, p.lastTeam(), c.age(), c.games() == null ? 0 : c.games(),
                    KaggleIdentity.positions(c.pos()), c.pts(), c.reb(), c.ast(), c.stl(), c.blk(), c.to(), c.tpm(),
                    c.fgm(), c.fga(), c.ftm(), c.fta()));
        }
        data.replaceSeasonLines(s, EraScoring.KAGGLE, lines);
    }

    /** A stat is "tracked" when the season file has it for more than half of that season's rows (research R2). */
    static Set<String> trackedStats(List<SeasonFileRow> rows) {
        Set<String> out = new HashSet<>();
        out.add("pts");
        if (rows.isEmpty()) return out;
        int n = rows.size();
        if (rows.stream().filter(r -> r.reb() != null).count() * 2 > n) out.add("reb");
        if (rows.stream().filter(r -> r.ast() != null).count() * 2 > n) out.add("ast");
        if (rows.stream().filter(r -> r.stl() != null).count() * 2 > n) out.add("stl");
        if (rows.stream().filter(r -> r.blk() != null).count() * 2 > n) out.add("blk");
        if (rows.stream().filter(r -> r.to() != null).count() * 2 > n) out.add("to");
        if (rows.stream().filter(r -> r.tpm() != null).count() * 2 > n) out.add("tpm");
        return out;
    }

    /** Counting-stat fantasy points of one game under the era's counted keys; a null stat is 0 (FR-007). */
    static double countingPoints(BoxRow r, Map<String, Double> scoring) {
        double sum = 0;
        for (String k : ERA_STATS) {
            Double w = scoring.get(k);
            Integer v = r.stat(k);
            if (w != null && v != null) sum += w * v;
        }
        return sum;
    }

    static double totalsPoints(SeasonFileRow r, Map<String, Double> scoring) {
        double sum = 0;
        sum += w(scoring, "pts", r.pts()) + w(scoring, "reb", r.reb()) + w(scoring, "ast", r.ast())
                + w(scoring, "stl", r.stl()) + w(scoring, "blk", r.blk()) + w(scoring, "to", r.to())
                + w(scoring, "tpm", r.tpm());
        return sum;
    }

    private static double w(Map<String, Double> scoring, String key, Double v) {
        Double weight = scoring.get(key);
        return weight == null || v == null ? 0 : weight * v;
    }

    static String label(int seasonStart) {
        return seasonStart + "-" + String.format("%02d", (seasonStart + 1) % 100);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
