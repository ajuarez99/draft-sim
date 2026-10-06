package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.refresh.RefreshProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A season's schedule, indexed by game and by week. Pure: built from the raw
 * maps, so it is testable against a trimmed fixture.
 *
 * <p>Lifted unchanged from {@code PlayerGameIngestService.Schedule}
 * (specs/017-nba-schedule-grid, T008) so the stored schedule and the per-game
 * ingest share one parser, with {@link #games()} and {@link #counts} added.
 *
 * <p>The home/away value's shape differs by sport in Sleeper's payload -- a
 * nested object carrying {@code team}, or a bare team-code string -- and
 * {@link #sideTeam} resolves either, by looking at the value rather than at
 * which sport it came from.
 */
public final class SportSchedule {

    private static final String POSTPONED = "postponed";
    private static final String CANCELED = "canceled";

    /** Statuses that will not become "played" in this week: settled for finality purposes. */
    private static final Set<String> SETTLED = Set.of("complete", POSTPONED, CANCELED);

    public record Game(String gameId, int week, LocalDate date, String status, String home, String away) {}

    private final Map<String, Game> byId = new HashMap<>();
    private final Map<Integer, List<Game>> byWeek = new TreeMap<>();

    private SportSchedule() {}

    public static SportSchedule parse(List<Map<String, Object>> raw) {
        SportSchedule s = new SportSchedule();
        for (Map<String, Object> g : raw) {
            Object id = g.get("game_id");
            Object wk = g.get("week");
            if (id == null || !(wk instanceof Number w)) continue;
            LocalDate date = null;
            Object d = g.get("date");
            if (d != null) {
                String ds = String.valueOf(d);
                try {
                    date = LocalDate.parse(ds.length() > 10 ? ds.substring(0, 10) : ds);
                } catch (RuntimeException e) {
                    date = null;
                }
            }
            Game game = new Game(String.valueOf(id), w.intValue(), date,
                    stringOrNull(g.get("status")), sideTeam(g.get("home")), sideTeam(g.get("away")));
            s.byId.put(game.gameId(), game);
            s.byWeek.computeIfAbsent(game.week(), k -> new ArrayList<>()).add(game);
        }
        return s;
    }

    /**
     * Every game, one per {@code game_id} (last row wins), for storage. Built from the
     * de-duplicated map, not the raw rows, so a repeated id can never reach the insert
     * twice (review F3).
     */
    public List<Game> games() {
        return new ArrayList<>(byId.values());
    }

    /**
     * Whether a game with this status is a scheduled game for counting purposes. A
     * {@code null} status counts: Sleeper didn't say, and dropping it would under-count.
     */
    public static boolean counts(String status) {
        return !POSTPONED.equals(status) && !CANCELED.equals(status);
    }

    /** A side's team code from either payload shape; null if it has none. */
    static String sideTeam(Object side) {
        if (side instanceof Map<?, ?> m) return stringOrNull(m.get("team"));
        return stringOrNull(side);
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** The last week with a game that has started: complete, or dated today or earlier. */
    int lastStartedWeek(LocalDate today) {
        int last = 0;
        for (Map.Entry<Integer, List<Game>> e : byWeek.entrySet()) {
            for (Game g : e.getValue()) {
                boolean started = "complete".equals(g.status())
                        || (g.date() != null && !g.date().isAfter(today));
                if (started) last = Math.max(last, e.getKey());
            }
        }
        return last;
    }

    /**
     * Whether this player's team was the away side of the game, from the
     * schedule. Unknown (null) rather than defaulted to home when the game or
     * team can't be matched.
     */
    Boolean isAway(String gameId, String team) {
        if (gameId == null || team == null) return null;
        Game g = byId.get(gameId);
        if (g == null) return null;
        if (team.equals(g.home())) return false;
        if (team.equals(g.away())) return true;
        return null;
    }

    /** A player's team in a game, given the opponent he faced; null if it can't be told. */
    String teamOf(String gameId, String opponent) {
        if (gameId == null || opponent == null) return null;
        Game g = byId.get(gameId);
        if (g == null) return null;
        if (opponent.equals(g.home())) return g.away();
        if (opponent.equals(g.away())) return g.home();
        return null;
    }

    /** Whether any game in the week is {@code complete}. */
    boolean hasCompleteGame(int week) {
        for (Game g : byWeek.getOrDefault(week, List.of())) {
            if ("complete".equals(g.status())) return true;
        }
        return false;
    }

    /**
     * Whether {@code team} played a completed game in the week. A merely
     * scheduled, postponed or canceled game is not a played one, so a rostered
     * player's missing entry for it is not an absence.
     */
    boolean teamPlayed(int week, String team) {
        for (Game g : byWeek.getOrDefault(week, List.of())) {
            if ("complete".equals(g.status()) && (team.equals(g.home()) || team.equals(g.away()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A week is final once every game in it is settled and the fetch happened
     * at least {@link RefreshProperties#WEEK_FINAL_AFTER} after the end of the
     * week's last game date (specs/009 research R6).
     *
     * <p>Two readings, both chosen to err toward refetching. "Settled" counts
     * {@code postponed} and {@code canceled} as well as {@code complete}:
     * measured 2026-09-28, the reference basketball season's schedule keeps
     * three postponed games in weeks 12 and 14 and one canceled game in week
     * 17 forever, so requiring {@code complete} alone would refetch those
     * weeks on every run and defeat the "an up-to-date season fetches
     * nothing" goal. A postponed game that is later played arrives as entries
     * in the week it was played (the entry's own {@code week}), and that week
     * stays non-final until its own games settle. And "after the last game
     * date" is measured from the <i>end</i> of that (date-only) day in UTC,
     * not its start, so the window never begins before the game finished.
     */
    boolean isFinal(int week, Instant fetchedAt) {
        List<Game> gs = byWeek.get(week);
        if (gs == null || gs.isEmpty()) return false;
        LocalDate last = null;
        for (Game g : gs) {
            if (g.status() == null || !SETTLED.contains(g.status())) return false;
            if (g.date() == null) return false;
            if (last == null || g.date().isAfter(last)) last = g.date();
        }
        Instant weekEnded = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return !fetchedAt.isBefore(weekEnded.plus(RefreshProperties.WEEK_FINAL_AFTER));
    }
}
