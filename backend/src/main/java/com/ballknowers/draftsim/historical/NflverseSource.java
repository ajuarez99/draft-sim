package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.NflHistoricalProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Reads the nflverse weekly player-stats files and the draft-picks file (spec 056 R1, T015, T017).
 * Data: nflverse/nflverse-data, CC BY 4.0.
 *
 * <p>One file per season ({@code stats_player_week_{season}.csv.gz}, about 1 MB). The parser streams records
 * through 053's {@link KaggleCsv} (quoted fields, an empty cell is {@code null}) and never holds the file. What it
 * does, in order of how much it matters:
 * <ul>
 *   <li><b>Fails loudly on a missing column</b> (no silent zeros from a renamed column) and on a second REG row for
 *       one player in one week.
 *   <li>Keeps REG rows only; QB/RB/WR/TE only, with FB and HB mapped to RB (R3: the file's position is the player's
 *       current listing, not necessarily his position that season).
 *   <li>Rows with no player id (blank, or the literal {@code 0}, which older files use for team-total rows) are
 *       counted, never stored. Only those with a skill position or a nonzero scored stat count as
 *       {@code droppedRows} (F7), because that is the number a user could be misled by.
 *   <li>Counts the season's distinct REG {@code game_id}s over EVERY row, so the loaded count is comparable with the
 *       real schedule (FR-014).
 * </ul>
 */
@Component
public class NflverseSource {

    /** The 13 scoring keys, in {@link StatOrder} order. One source: {@link NflHistoricalSport#COUNTING}. */
    public static final List<String> KEYS = NflHistoricalSport.COUNTING;

    private static final List<String> REQUIRED_STATS = List.of("passing_yards", "passing_tds",
            "passing_interceptions", "passing_2pt_conversions", "sack_fumbles_lost", "rushing_yards", "rushing_tds",
            "rushing_fumbles_lost", "rushing_2pt_conversions", "receptions", "receiving_yards", "receiving_tds",
            "receiving_fumbles_lost", "receiving_2pt_conversions", "special_teams_tds");
    private static final List<String> REQUIRED = buildRequired();

    private static List<String> buildRequired() {
        List<String> r = new ArrayList<>(List.of("player_id", "player_display_name", "position", "season", "week",
                "season_type", "game_id", "team", "opponent_team", "fantasy_points_ppr"));
        r.addAll(REQUIRED_STATS);
        return List.copyOf(r);
    }

    private static final List<String> DRAFT_REQUIRED = List.of("season", "round", "pick", "gsis_id", "position");
    private static final Set<String> SKILL = Set.of("QB", "RB", "WR", "TE");

    private final NflHistoricalProperties props;

    public NflverseSource(NflHistoricalProperties props) {
        this.props = props;
    }

    // ---- results -------------------------------------------------------------------------------------

    /** One kept REG game row. {@code stats} is in {@link #KEYS} order. {@code srcPpr} is null when the source has none. */
    public record WeekRow(String gsisId, String name, String position, int week, String gameId, String team,
                          String opponent, double[] stats, Double srcPpr) {}

    /** One player's aggregated REG season (R5): games = REG weeks with a row, team = the last REG row's. */
    public record SeasonAgg(String gsisId, String name, String position, String team, int games, double[] totals) {}

    /**
     * @param regGames       distinct REG game ids over every row of the file
     * @param maxWeek        the highest REG week number
     * @param rawBlankIdRows REG rows with no player id (team totals and a few crumbs)
     * @param droppedRows    the subset of those with a skill position or a nonzero scored stat (F7)
     * @param nonSkillRows   REG rows that have a player id but a position the game does not use
     * @param fileSha256     of the downloaded bytes; null when parsed from a bare reader
     */
    public record ParsedSeason(int season, List<WeekRow> weeks, List<SeasonAgg> seasonLines, int regGames,
                               int maxWeek, int rawBlankIdRows, int droppedRows, int nonSkillRows,
                               String fileSha256, long fileBytes) {
        ParsedSeason withFile(String sha, long bytes) {
            return new ParsedSeason(season, weeks, seasonLines, regGames, maxWeek, rawBlankIdRows, droppedRows,
                    nonSkillRows, sha, bytes);
        }
    }

    /** One player's first NFL draft slot (overall pick), skill positions only, FB/HB mapped to RB. */
    public record DraftPickRow(String gsisId, int season, int round, int pick, String position) {}

    // ---- parsing -------------------------------------------------------------------------------------

    /**
     * Parses one season's file. Pure given its input (no network, no database), so it is unit-tested over a
     * hand-written fixture.
     *
     * @throws IllegalStateException a column is missing, a row is from another season, a number is unreadable, or a
     *                               player has two REG rows in one week
     */
    public ParsedSeason parse(int season, Reader reader) {
        try (KaggleCsv csv = new KaggleCsv(reader)) {
            List<String> missing = new ArrayList<>();
            for (String col : REQUIRED) if (csv.indexOf(col) < 0) missing.add(col);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("nflverse stats file for season " + season
                        + " is missing expected columns " + missing + "; refusing to import it");
            }
            int cId = csv.indexOf("player_id"), cDisplay = csv.indexOf("player_display_name"),
                    cName = csv.indexOf("player_name"), cPos = csv.indexOf("position"),
                    cSeason = csv.indexOf("season"), cWeek = csv.indexOf("week"),
                    cType = csv.indexOf("season_type"), cGame = csv.indexOf("game_id"), cTeam = csv.indexOf("team"),
                    cOpp = csv.indexOf("opponent_team"), cPpr = csv.indexOf("fantasy_points_ppr");
            int[] cStat = new int[REQUIRED_STATS.size()];
            for (int i = 0; i < cStat.length; i++) cStat[i] = csv.indexOf(REQUIRED_STATS.get(i));

            List<WeekRow> weeks = new ArrayList<>();
            Set<String> gameIds = new HashSet<>();
            Set<String> seen = new HashSet<>();
            int maxWeek = 0, rawBlank = 0, dropped = 0, nonSkill = 0;
            String[] r;
            while ((r = csv.next()) != null) {
                if (!"REG".equals(cell(r, cType))) continue;
                int rowSeason = intCell(r, cSeason, "season");
                if (rowSeason != season) {
                    throw new IllegalStateException("nflverse file for season " + season + " has a row for season "
                            + rowSeason);
                }
                int week = intCell(r, cWeek, "week");
                maxWeek = Math.max(maxWeek, week);
                String gameId = cell(r, cGame);
                if (gameId != null) gameIds.add(gameId);

                String position = skillPosition(cell(r, cPos));
                double[] stats = stats(r, cStat);
                String id = cell(r, cId);
                if (id == null || id.equals("0") || id.equalsIgnoreCase("NA")) {
                    rawBlank++;
                    if (position != null || anyNonZero(stats)) dropped++;
                    continue;
                }
                if (position == null) {
                    nonSkill++;
                    continue;
                }
                if (!seen.add(id + "|" + week)) {
                    throw new IllegalStateException("nflverse file for season " + season + " has two REG rows for"
                            + " player " + id + " in week " + week + "; refusing to import it");
                }
                String name = cell(r, cDisplay);
                if (name == null && cName >= 0) name = cell(r, cName);
                if (name == null) name = id;
                weeks.add(new WeekRow(id, name, position, week, gameId, franchiseCodeIn(season, cell(r, cTeam)),
                        franchiseCodeIn(season, cell(r, cOpp)), stats,
                        number(r, cPpr, "fantasy_points_ppr", true)));
            }
            return new ParsedSeason(season, weeks, aggregate(weeks), gameIds.size(), maxWeek, rawBlank, dropped,
                    nonSkill, null, 0);
        }
    }

    /**
     * Schedule fact: franchise relocations. nflverse normalizes {@code team} and {@code opponent_team} to TODAY's
     * franchise codes for every season (a 2004 Tomlinson row says LAC), while {@code game_id} keeps the code of the
     * day. Map the three moves back so a historical card shows the team the player played for:
     * LAC -> SD through 2016, LA -> STL through 2015, LV -> OAK through 2019. Any other code (or null) is unchanged.
     */
    static String franchiseCodeIn(int season, String code) {
        if (code == null) return null;
        return switch (code) {
            case "LAC" -> season <= 2016 ? "SD" : code;
            case "LA" -> season <= 2015 ? "STL" : code;
            case "LV" -> season <= 2019 ? "OAK" : code;
            default -> code;
        };
    }

    private static List<SeasonAgg> aggregate(List<WeekRow> weeks) {
        record Acc(String name, String position, String team, int lastWeek, int games, double[] totals) {}
        Map<String, Acc> acc = new LinkedHashMap<>();
        for (WeekRow w : weeks) {
            Acc a = acc.get(w.gsisId());
            if (a == null) {
                acc.put(w.gsisId(), new Acc(w.name(), w.position(), w.team(), w.week(), 1, w.stats().clone()));
                continue;
            }
            for (int i = 0; i < a.totals().length; i++) a.totals()[i] += w.stats()[i];
            // The LAST REG row's team and position and name win, by week number rather than by file order.
            Acc next = w.week() >= a.lastWeek()
                    ? new Acc(w.name(), w.position(), w.team(), w.week(), a.games() + 1, a.totals())
                    : new Acc(a.name(), a.position(), a.team(), a.lastWeek(), a.games() + 1, a.totals());
            acc.put(w.gsisId(), next);
        }
        List<SeasonAgg> out = new ArrayList<>(acc.size());
        for (Map.Entry<String, Acc> e : acc.entrySet()) {
            Acc a = e.getValue();
            out.add(new SeasonAgg(e.getKey(), a.name(), a.position(), a.team(), a.games(), a.totals()));
        }
        return out;
    }

    /** The 13 scoring stats in {@link #KEYS} order from the 15 source columns. NA/empty is 0. */
    private static double[] stats(String[] r, int[] c) {
        // REQUIRED_STATS order: 0 passing_yards, 1 passing_tds, 2 passing_interceptions, 3 passing_2pt,
        // 4 sack_fumbles_lost, 5 rushing_yards, 6 rushing_tds, 7 rushing_fumbles_lost, 8 rushing_2pt,
        // 9 receptions, 10 receiving_yards, 11 receiving_tds, 12 receiving_fumbles_lost, 13 receiving_2pt,
        // 14 special_teams_tds
        double[] s = new double[KEYS.size()];
        s[0] = num(r, c[0], REQUIRED_STATS.get(0));    // pass_yd
        s[1] = num(r, c[1], REQUIRED_STATS.get(1));    // pass_td
        s[2] = num(r, c[2], REQUIRED_STATS.get(2));    // pass_int
        s[3] = num(r, c[5], REQUIRED_STATS.get(5));    // rush_yd
        s[4] = num(r, c[6], REQUIRED_STATS.get(6));    // rush_td
        s[5] = num(r, c[9], REQUIRED_STATS.get(9));    // rec
        s[6] = num(r, c[10], REQUIRED_STATS.get(10));  // rec_yd
        s[7] = num(r, c[11], REQUIRED_STATS.get(11));  // rec_td
        s[8] = num(r, c[4], REQUIRED_STATS.get(4)) + num(r, c[7], REQUIRED_STATS.get(7))
                + num(r, c[12], REQUIRED_STATS.get(12));   // fum_lost = sack + rushing + receiving fumbles lost
        s[9] = num(r, c[3], REQUIRED_STATS.get(3));    // pass_2pt
        s[10] = num(r, c[8], REQUIRED_STATS.get(8));   // rush_2pt
        s[11] = num(r, c[13], REQUIRED_STATS.get(13)); // rec_2pt
        s[12] = num(r, c[14], REQUIRED_STATS.get(14)); // st_td
        return s;
    }

    private static boolean anyNonZero(double[] s) {
        for (double v : s) if (v != 0) return true;
        return false;
    }

    /** QB/RB/WR/TE, with FB and HB mapped to RB; null for anything else (OL, DB, K, blank...). */
    static String skillPosition(String raw) {
        if (raw == null) return null;
        String p = raw.trim().toUpperCase();
        if (p.equals("FB") || p.equals("HB")) p = "RB";
        return SKILL.contains(p) ? p : null;
    }

    private static String cell(String[] r, int i) {
        return i < 0 || i >= r.length ? null : r[i];
    }

    private static int intCell(String[] r, int i, String name) {
        String v = cell(r, i);
        if (v == null) throw new IllegalStateException("nflverse row has no " + name);
        try {
            return (int) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("nflverse row has an unreadable " + name + ": '" + v + "'");
        }
    }

    private static double num(String[] r, int i, String name) {
        Double d = number(r, i, name, false);
        return d == null ? 0.0 : d;
    }

    /** An empty cell or "NA" is 0 (null when {@code nullable}); anything else unreadable fails the season loudly. */
    private static Double number(String[] r, int i, String name, boolean nullable) {
        String v = cell(r, i);
        if (v == null || v.equalsIgnoreCase("NA") || v.isBlank()) return nullable ? null : 0.0;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("nflverse row has an unreadable " + name + ": '" + v + "'");
        }
    }

    // ---- the draft picks file ------------------------------------------------------------------------

    /**
     * Parses {@code draft_picks.csv}: skill positions with a GSIS id only, one row per player (his first draft, in
     * case a player appears twice). Fails loudly on a missing column.
     */
    public List<DraftPickRow> parseDraftPicks(Reader reader) {
        try (KaggleCsv csv = new KaggleCsv(reader)) {
            List<String> missing = new ArrayList<>();
            for (String col : DRAFT_REQUIRED) if (csv.indexOf(col) < 0) missing.add(col);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("nflverse draft_picks.csv is missing expected columns " + missing);
            }
            int cSeason = csv.indexOf("season"), cRound = csv.indexOf("round"), cPick = csv.indexOf("pick"),
                    cGsis = csv.indexOf("gsis_id"), cPos = csv.indexOf("position");
            Map<String, DraftPickRow> first = new HashMap<>();
            String[] r;
            while ((r = csv.next()) != null) {
                String id = cell(r, cGsis);
                String pos = skillPosition(cell(r, cPos));
                if (id == null || pos == null) continue;
                DraftPickRow row = new DraftPickRow(id, intCell(r, cSeason, "season"), intCell(r, cRound, "round"),
                        intCell(r, cPick, "pick"), pos);
                DraftPickRow prev = first.get(id);
                if (prev == null || row.season() < prev.season()
                        || (row.season() == prev.season() && row.pick() < prev.pick())) first.put(id, row);
            }
            List<DraftPickRow> out = new ArrayList<>(first.values());
            out.sort(java.util.Comparator.comparingInt(DraftPickRow::season).thenComparingInt(DraftPickRow::pick));
            return out;
        }
    }

    // ---- fetching ------------------------------------------------------------------------------------

    /** Downloads and parses one season. Admin-triggered only, never on a user request. */
    public ParsedSeason fetchSeason(int season) throws IOException {
        String url = props.statsUrlTemplate().replace("{season}", String.valueOf(season));
        Path tmp = Files.createTempFile("nflverse-" + season + "-", ".csv.gz");
        try {
            download(url, tmp);
            String sha = KaggleHistoricalSource.sha256(tmp);
            long bytes = Files.size(tmp);
            try (InputStream in = open(tmp)) {
                return parse(season, new InputStreamReader(in, StandardCharsets.UTF_8)).withFile(sha, bytes);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public List<DraftPickRow> fetchDraftPicks() throws IOException {
        Path tmp = Files.createTempFile("nflverse-draft-picks-", ".csv");
        try {
            download(props.draftPicksUrl(), tmp);
            try (InputStream in = open(tmp)) {
                return parseDraftPicks(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** gzip if the file starts with the gzip magic bytes, plain otherwise (a test fixture may be either). */
    private static InputStream open(Path file) throws IOException {
        PushbackInputStream in = new PushbackInputStream(Files.newInputStream(file), 2);
        int b0 = in.read(), b1 = in.read();
        if (b1 != -1) in.unread(b1);
        if (b0 != -1) in.unread(b0);
        return b0 == 0x1f && b1 == 0x8b ? new GZIPInputStream(in, 1 << 16) : in;
    }

    private static void download(String url, Path target) throws IOException {
        URI uri = URI.create(url);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Files.copy(Path.of(uri), target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // The same redirect-following client as 053's Kaggle download (F11): a release asset 302s to a CDN host.
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<Path> resp = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(target));
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("nflverse download " + url + " returned HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + url, e);
        }
    }
}
