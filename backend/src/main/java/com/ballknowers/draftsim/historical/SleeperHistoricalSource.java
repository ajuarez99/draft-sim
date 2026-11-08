package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalProperties;
import com.ballknowers.draftsim.config.NbaGameProperties;
import com.ballknowers.draftsim.engine.ScheduleGridService;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.ingest.SleeperPlayerStatsClient;
import com.ballknowers.draftsim.sport.BasketballRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The Sleeper half of the historical import (spec 053 R3, F11, F19, T012): seasons 2017..2025 of weekly game
 * logs, and 2016..2025 season totals for the prior-season cards (2016 is cards only; its box scores come from
 * Kaggle).
 *
 * <p>Sleeper omits zero-valued stats, so a missing key is 0 and the era rule does not apply (research R2).
 * Weeks are NOT Sleeper's: each game is bucketed from its date by {@link HistoricalWeeks}, the same as Kaggle.
 */
@Component
public class SleeperHistoricalSource {

    private static final Logger log = LoggerFactory.getLogger(SleeperHistoricalSource.class);

    public static final int FIRST_TOTALS_SEASON = 2014, FIRST_WEEKLY_SEASON = 2017, LAST_SEASON = 2025;

    /** Fetched unconditionally, 1..30, keeping the non-empty ones (tasks T012, amended after analyze U1). */
    static final int MAX_WEEK = 30;

    /** Season start -> last date that counts. 2019-20 stops at the shutdown (FR-010). */
    static final Map<Integer, LocalDate> LAST_COUNTED = Map.of(2019, LocalDate.of(2020, 3, 11));

    /** Column order of {@link SleeperGame#v}. */
    static final List<String> STAT_KEYS = List.of("pts", "reb", "ast", "stl", "blk", "to", "tpm", "fgm", "fga",
            "ftm", "fta", "tf", "ff", "dd", "td", "bonus_pt_40p", "bonus_pt_50p", "bonus_reb_20p", "bonus_ast_15p");

    /** One played game of one player, compact (225k of these are held at once). A missing stat key is 0. */
    record SleeperGame(String playerId, long gameId, String rawGameId, LocalDate date, String team, int[] v) {}

    /** Who a Sleeper id is: from /v1/players/nba, else from the player object a weekly entry embeds. */
    record Meta(String name, List<String> positions, LocalDate birthDate) {}

    /** What parsing one batch of weekly entries produced, plus what it dropped and why. */
    static final class Parsed {
        final List<SleeperGame> games = new ArrayList<>();
        final Map<String, Meta> embedded = new HashMap<>();
        int teamEntries, emptyStats, afterCutoff, hashedIds, noDate, excludedRows, exhibitionRows, exhibitionGames;
        /** Raw Sleeper game ids dropped before hashing (NBA Cup finals; {@code NbaGameProperties}). */
        Set<String> excludedIds = Set.of();
    }

    private final SleeperPlayerStatsClient stats;
    private final SleeperClient sleeper;
    private final BasketballRules rules;
    private final HistoricalProperties props;
    private final HistoricalDataRepository data;
    private final TransactionTemplate tx;
    private final NbaGameProperties nbaGames;

    public SleeperHistoricalSource(SleeperPlayerStatsClient stats, SleeperClient sleeper, BasketballRules rules,
                                   HistoricalProperties props, HistoricalDataRepository data, TransactionTemplate tx,
                                   NbaGameProperties nbaGames) {
        this.nbaGames = nbaGames;
        this.stats = stats;
        this.sleeper = sleeper;
        this.rules = rules;
        this.props = props;
        this.data = data;
        this.tx = tx;
    }

    // ---- pure parsing (unit-tested with fixtures) ----------------------------------------------------

    /**
     * Turns one week of raw entries into compact games. Drops {@code TEAM_*} pseudo-players, entries whose stats
     * are empty (a DNP: {@code played} is {@code BasketballRules.playedIn} in production), and games dated after
     * {@code lastCounted} (null = no cut). Accumulates into {@code into}.
     */
    static void parseWeek(List<Map<String, Object>> entries, Predicate<Map<String, ?>> played, LocalDate lastCounted,
                          Parsed into) {
        for (Map<String, Object> e : entries) {
            Object pid = e.get("player_id");
            if (pid == null) continue;
            String playerId = pid.toString();
            if (playerId.startsWith("TEAM_")) {
                into.teamEntries++;
                continue;
            }
            Object statsObj = e.get("stats");
            @SuppressWarnings("unchecked")
            Map<String, Object> st = statsObj instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            if (st == null || !played.test(st)) {
                into.emptyStats++;
                continue;
            }
            LocalDate date = parseDate(e.get("date"));
            if (date == null) {
                into.noDate++;
                continue;
            }
            if (lastCounted != null && date.isAfter(lastCounted)) {
                into.afterCutoff++;
                continue;
            }
            Object gid = e.get("game_id");
            long gameId = 0;
            String raw = gid == null ? null : gid.toString().trim();
            if (raw != null && into.excludedIds.contains(raw)) {
                into.excludedRows++;
                continue;
            }
            if (gid != null) {
                String g = raw;
                try {
                    gameId = Long.parseLong(g);
                } catch (NumberFormatException ex) {
                    gameId = stableHash(g);
                    into.hashedIds++;
                }
            }
            int[] v = new int[STAT_KEYS.size()];
            for (int i = 0; i < v.length; i++) {
                Object val = st.get(STAT_KEYS.get(i));
                int n = val instanceof Number num ? (int) Math.round(num.doubleValue()) : 0;
                if (i >= 13) n = n >= 1 ? 1 : 0; // dd, td and the bonuses are 0/1 flags
                v[i] = n;
            }
            // Sleeper 2017-2019 never computed the bonus flags (absence meant "not computed", not 0): derive them
            // from the box stats with the one shared derivation, OR-ed with Sleeper's own flag where it sent one.
            var dv = KaggleIdentity.derive(v[0], v[1], v[2], v[3], v[4]);
            v[STAT_KEYS.indexOf("dd")] |= dv.dd();
            v[STAT_KEYS.indexOf("td")] |= dv.td();
            v[STAT_KEYS.indexOf("bonus_pt_40p")] |= dv.bonusPt40();
            v[STAT_KEYS.indexOf("bonus_pt_50p")] |= dv.bonusPt50();
            v[STAT_KEYS.indexOf("bonus_reb_20p")] |= dv.bonusReb20();
            v[STAT_KEYS.indexOf("bonus_ast_15p")] |= dv.bonusAst15();
            Object team = e.get("team");
            into.games.add(new SleeperGame(playerId, gameId, raw, date, team == null ? null : team.toString(), v));
            if (e.get("player") instanceof Map<?, ?> pm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) pm;
                into.embedded.putIfAbsent(playerId, meta(p));
            }
        }
    }

    /**
     * Drops exhibition games (All-Star, Rising Stars, ...) from {@code p.games}, using the ONE rule the repo already
     * has: {@link ScheduleGridService#exhibitionGames} (spec 017, a side with under a quarter of the median team's
     * game count). One schedule game per raw Sleeper game id, its sides being the team codes of the rows that carry
     * it. Rows with no team code are kept (a null side is never an exhibition side). Sleeper sent no TEAM_ rows in
     * 2017-2019, so the box-level rule ({@code NbaGameLines.isTeamGame}) cannot be used here.
     */
    static void dropExhibitions(Parsed p) {
        Map<String, Set<String>> sides = new LinkedHashMap<>();
        Map<String, LocalDate> dateOf = new HashMap<>();
        for (SleeperGame g : p.games) {
            if (g.rawGameId() == null) continue;
            Set<String> t = sides.computeIfAbsent(g.rawGameId(), k -> new java.util.TreeSet<>());
            if (g.team() != null) t.add(g.team());
            dateOf.putIfAbsent(g.rawGameId(), g.date());
        }
        List<SportSchedule.Game> schedule = new ArrayList<>(sides.size());
        for (Map.Entry<String, Set<String>> e : sides.entrySet()) {
            List<String> t = new ArrayList<>(e.getValue());
            schedule.add(new SportSchedule.Game(e.getKey(), 0, dateOf.get(e.getKey()), "complete",
                    t.size() > 0 ? t.get(0) : null, t.size() > 1 ? t.get(1) : null));
        }
        Predicate<SportSchedule.Game> exhibition = ScheduleGridService.exhibitionGames(schedule);
        Set<String> dropped = new HashSet<>();
        for (SportSchedule.Game g : schedule) if (exhibition.test(g)) dropped.add(g.gameId());
        if (dropped.isEmpty()) return;
        int before = p.games.size();
        p.games.removeIf(g -> g.rawGameId() != null && dropped.contains(g.rawGameId()));
        p.exhibitionRows += before - p.games.size();
        p.exhibitionGames += dropped.size();
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        try {
            return LocalDate.parse(o.toString().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** FNV-1a 64 over the UTF-8 bytes, clamped non-negative: a stable id for a non-numeric Sleeper game id. */
    static long stableHash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h & Long.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    static Meta meta(Map<String, Object> p) {
        String name = null;
        Object full = p.get("full_name");
        if (full != null && !full.toString().isBlank()) name = full.toString();
        else {
            String f = p.get("first_name") == null ? "" : p.get("first_name").toString();
            String l = p.get("last_name") == null ? "" : p.get("last_name").toString();
            String joined = (f + " " + l).trim();
            if (!joined.isEmpty()) name = joined;
        }
        List<String> raw = new ArrayList<>();
        if (p.get("fantasy_positions") instanceof List<?> l) {
            for (Object o : l) if (o != null) raw.add(o.toString());
        } else if (p.get("position") != null) {
            raw.add(p.get("position").toString());
        }
        return new Meta(name, positionsOf(raw, p.get("position") == null ? null : p.get("position").toString()),
                parseDate(p.get("birth_date")));
    }

    /** Sleeper positions to engine positions; its own {@code position} goes first when already eligible (spec 026). */
    static List<String> positionsOf(List<String> fantasyPositions, String position) {
        LinkedHashSet<String> mapped = new LinkedHashSet<>();
        for (String raw : fantasyPositions) {
            switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "PG", "SG", "SF", "PF", "C" -> mapped.add(raw.trim().toUpperCase(Locale.ROOT));
                case "G" -> {
                    mapped.add("PG");
                    mapped.add("SG");
                }
                case "F" -> {
                    mapped.add("SF");
                    mapped.add("PF");
                }
                default -> { /* DEF and other archive artifacts are dropped */ }
            }
        }
        List<String> out = new ArrayList<>(mapped);
        if (position != null && out.size() > 1) {
            String p = position.trim().toUpperCase(Locale.ROOT);
            if (out.remove(p)) out.addFirst(p);
        }
        return List.copyOf(out);
    }

    /** Age on Oct 1 of the season's start year (F19); null when the birth date is unknown. */
    static Integer ageOnOctFirst(LocalDate birth, int seasonStart) {
        return birth == null ? null : Period.between(birth, LocalDate.of(seasonStart, 10, 1)).getYears();
    }

    // ---- the import ----------------------------------------------------------------------------------

    public void importAll(ImportReport report) {
        Map<String, Map<String, Object>> all = sleeper.allPlayers("nba");
        Map<String, Meta> known = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : all.entrySet()) {
            if (e.getValue() != null) known.put(e.getKey(), meta(e.getValue()));
        }
        log.info("historical import: {} Sleeper players loaded", known.size());

        // Phase A: every weekly season, held compactly (about 25k games a season).
        Map<Integer, Parsed> weekly = new TreeMap<>();
        Map<String, Object> weekNotes = new LinkedHashMap<>();
        for (int y = FIRST_WEEKLY_SEASON; y <= LAST_SEASON; y++) {
            Parsed parsed = new Parsed();
            parsed.excludedIds = nbaGames.excludedGameIdSet();
            int first = -1, last = -1;
            for (int w = 1; w <= MAX_WEEK; w++) {
                int before = parsed.games.size();
                List<Map<String, Object>> entries = stats.week("nba", y, w);
                parseWeek(entries, rules::playedIn, LAST_COUNTED.get(y), parsed);
                if (parsed.games.size() > before) {
                    if (first < 0) first = w;
                    last = w;
                }
            }
            dropExhibitions(parsed);
            weekly.put(y, parsed);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("firstNonEmptyWeek", first < 0 ? null : first);
            n.put("lastNonEmptyWeek", last < 0 ? null : last);
            n.put("playedEntries", parsed.games.size());
            n.put("teamEntriesDropped", parsed.teamEntries);
            n.put("emptyStatsDropped", parsed.emptyStats);
            n.put("droppedAfterCutoff", parsed.afterCutoff);
            n.put("hashedGameIds", parsed.hashedIds);
            n.put("cupFinalRowsDropped", parsed.excludedRows);
            n.put("exhibitionGamesDropped", parsed.exhibitionGames);
            n.put("exhibitionRowsDropped", parsed.exhibitionRows);
            weekNotes.put(String.valueOf(y), n);
            log.info("historical import: Sleeper {} weekly {} (weeks {}..{})", y, n, first, last);
        }
        report.note("sleeperWeekly", weekNotes);

        Map<String, Meta> embedded = new HashMap<>();
        weekly.values().forEach(p -> p.embedded.forEach(embedded::putIfAbsent));

        // Phase B: one transaction per season.
        for (int y = FIRST_TOTALS_SEASON; y <= LAST_SEASON; y++) {
            importSeason(y, weekly.get(y), known, embedded, report);
        }
    }

    private Meta metaOf(String id, Map<String, Meta> known, Map<String, Meta> embedded) {
        Meta k = known.get(id);
        Meta e = embedded.get(id);
        if (k == null) return e;
        if (e == null) return k;
        return new Meta(k.name() != null ? k.name() : e.name(),
                !k.positions().isEmpty() ? k.positions() : e.positions(),
                k.birthDate() != null ? k.birthDate() : e.birthDate());
    }

    private void importSeason(int y, Parsed parsed, Map<String, Meta> known, Map<String, Meta> embedded,
                              ImportReport report) {
        try {
            Map<String, Map<String, Object>> totals = stats.seasonTotals("nba", y);
            List<String> reasons = new ArrayList<>();
            Set<String> withGames = new LinkedHashSet<>();
            Map<String, String> lastTeam = new HashMap<>();
            Map<String, Integer> gamesPlayed = new HashMap<>();
            if (parsed != null) {
                for (SleeperGame g : parsed.games) {
                    withGames.add(g.playerId());
                    lastTeam.put(g.playerId(), g.team());
                    gamesPlayed.merge(g.playerId(), 1, Integer::sum);
                }
            }
            if (totals.values().stream().noneMatch(m -> !m.isEmpty())) {
                report.failed(y, "SLEEPER", List.of("no season totals for " + y));
                return;
            }
            for (String id : withGames) {
                Meta m = metaOf(id, known, embedded);
                if (m == null || m.positions().isEmpty()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("seasonStart", y);
                    e.put("sleeperId", id);
                    e.put("name", m == null ? null : m.name());
                    report.emptyPosition(e);
                    reasons.add("empty positions: Sleeper player " + id);
                }
            }
            if (!reasons.isEmpty()) {
                report.failed(y, "SLEEPER", reasons);
                return;
            }

            int[] counts = new int[4]; // lines, games, skippedUnnamed, skippedNoGp
            LocalDate opening = null, last = null;
            HistoricalWeeks weeks = null;
            Set<Long> gameIds = new HashSet<>();
            if (parsed != null && !parsed.games.isEmpty()) {
                List<LocalDate> dates = new ArrayList<>();
                for (SleeperGame g : parsed.games) {
                    dates.add(g.date());
                    gameIds.add(g.gameId());
                }
                opening = dates.stream().min(LocalDate::compareTo).orElseThrow();
                last = dates.stream().max(LocalDate::compareTo).orElseThrow();
                weeks = HistoricalWeeks.of(dates, opening, last);
            }
            final LocalDate openingF = opening, lastF = last;
            final HistoricalWeeks weeksF = weeks;
            tx.executeWithoutResult(st -> {
                Map<String, Long> idOf = new HashMap<>();
                List<SeasonLine> lines = new ArrayList<>();
                for (Map.Entry<String, Map<String, Object>> e : totals.entrySet()) {
                    String id = e.getKey();
                    Map<String, Object> t = e.getValue();
                    if (id.startsWith("TEAM_") || t.isEmpty()) continue;
                    Meta m = metaOf(id, known, embedded);
                    if (m == null || m.name() == null || m.positions().isEmpty()) {
                        counts[2]++;
                        continue;
                    }
                    Integer gp = intOf(t.get("gp"));
                    if (gp == null) gp = gamesPlayed.get(id);
                    if (gp == null || gp <= 0) {
                        counts[3]++;
                        continue;
                    }
                    long pid = idOf.computeIfAbsent(id, k -> data.upsertPlayer(EraScoring.SLEEPER, k, m.name()));
                    lines.add(new SeasonLine(pid, y, lastTeam.get(id), ageOnOctFirst(m.birthDate(), y), gp,
                            m.positions(), d(t, "pts"), d(t, "reb"), d(t, "ast"), d(t, "stl"), d(t, "blk"),
                            d(t, "to"), d(t, "tpm"), d(t, "fgm"), d(t, "fga"), d(t, "ftm"), d(t, "fta")));
                }
                data.replaceSeasonLines(y, EraScoring.SLEEPER, lines);
                counts[0] = lines.size();
                if (y < FIRST_WEEKLY_SEASON || weeksF == null) return;

                List<GameLine> games = new ArrayList<>(parsed.games.size());
                Set<String> seen = new HashSet<>();
                for (SleeperGame g : parsed.games) {
                    Meta m = metaOf(g.playerId(), known, embedded);
                    long pid = idOf.computeIfAbsent(g.playerId(),
                            k -> data.upsertPlayer(EraScoring.SLEEPER, k, m.name() != null ? m.name() : "Sleeper #" + k));
                    if (!seen.add(pid + ":" + g.gameId())) continue;
                    var wk = weeksF.weekOf(g.date());
                    if (wk.isEmpty()) continue;
                    int[] v = g.v();
                    games.add(new GameLine(pid, g.gameId(), y, g.date(), wk.getAsInt(), g.team(), v[0], v[1], v[2],
                            v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15],
                            v[16], v[17], v[18]));
                }
                data.replaceSeasonGames(y, games);
                counts[1] = games.size();
                data.upsertSeason(new SeasonRow("NBA", y, KaggleHistoricalSource.label(y), EraScoring.SLEEPER, openingF, lastF,
                        weeksF.weekCount(), gameIds.size(), new ArrayList<>(props.scoring().keySet()), "{}", "{}",
                        "PRIOR_SEASON_POINTS", OffsetDateTime.now(ZoneOffset.UTC), null, 0, null));
            });

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seasonStart", y);
            m.put("source", "SLEEPER");
            m.put("linesOnly", y < FIRST_WEEKLY_SEASON);
            m.put("playerSeasons", counts[0]);
            m.put("gameRows", counts[1]);
            m.put("skippedUnnamedOrNoPositionLines", counts[2]);
            m.put("skippedNoGamesPlayedLines", counts[3]);
            if (weeksF != null) {
                m.put("games", gameIds.size());
                m.put("weeks", weeksF.weekCount());
                m.put("openingNight", openingF.toString());
                m.put("lastGameDate", lastF.toString());
                m.put("countedStats", new ArrayList<>(props.scoring().keySet()));
                m.put("gameIdsHashed", parsed.hashedIds > 0);
            }
            report.season(m);
        } catch (RuntimeException e) {
            log.warn("historical import: Sleeper season {} failed: {}", y, e.toString(), e);
            report.failed(y, "SLEEPER", List.of("exception: " + e));
        }
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? (int) Math.round(n.doubleValue()) : null;
    }

    /** A Sleeper totals stat: a missing key is 0 (Sleeper omits zeros). */
    private static Double d(Map<String, Object> t, String key) {
        return t.get(key) instanceof Number n ? n.doubleValue() : 0.0;
    }
}
