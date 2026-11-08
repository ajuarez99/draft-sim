package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.NflverseSource.ParsedSeason;
import com.ballknowers.draftsim.historical.NflverseSource.SeasonAgg;
import com.ballknowers.draftsim.historical.NflverseSource.WeekRow;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A made-up football world for integration tests (spec 056 T022), at season starts no real import produces
 * (1893..1895 -- the same numbers the basketball {@link HistoricalSyntheticSeason} uses, on purpose, so the two sports
 * can coexist in one database and prove the {@code (sport, season_start)} key).
 *
 * <p>Season 1895 is the drafted one. Its own lines carry SENTINEL values (team ZZZ, 91 games, 7777 passing yards, ...)
 * so a test can prove none of them reaches the draft room (FR-004). 240 veterans with S-1 lines (every other one also
 * has an S-2 line), 10 rookies with only 1895 rows and NFL draft picks, and 4 weeks of games. Player {@code i} has a
 * bye in week {@code (i % 4) + 1}. Players 10 and 11 share the display name "Twin Name" (SC-007).
 *
 * <p>Players are source-keyed with {@link #PREFIX}, so {@link #clean} removes exactly what {@link #seed} wrote.
 */
public final class NflSyntheticSeason {

    public static final int S2 = 1893, S1 = 1894, S = 1895;
    public static final String PREFIX = "it-nfl-";
    public static final int VETERANS = 240, ROOKIES = 10, WEEKS = 4;
    public static final String TWIN = "Twin Name";

    private NflSyntheticSeason() {}

    /** QB 30, RB 90, WR 90, TE 30 among the 240 veterans; rookies cycle RB, WR, QB, TE. */
    public static String position(int i) {
        if (i >= VETERANS) return new String[]{"RB", "WR", "QB", "TE"}[(i - VETERANS) % 4];
        int m = i % 8;
        if (m == 0) return "QB";
        if (m <= 3) return "RB";
        if (m <= 6) return "WR";
        return "TE";
    }

    public static String name(int i) {
        return i == 10 || i == 11 ? TWIN : "Synthetic " + i;
    }

    public static boolean hasGame(int i, int week) {
        return week != (i % 4) + 1;
    }

    /** The 13 stats of player {@code i} in a week, in {@link NflverseSource#KEYS} order. */
    public static double[] weekStats(int i, int w) {
        double[] s = new double[NflverseSource.KEYS.size()];
        switch (position(i)) {
            case "QB" -> {
                s[0] = 150 + (i * 7 + w * 13) % 150;   // pass_yd
                s[1] = (i + w) % 4;                    // pass_td
                s[2] = (i * 3 + w) % 3;                // pass_int
                s[3] = (i + w) % 20;                   // rush_yd
            }
            case "RB" -> {
                s[3] = 20 + (i * 11 + w * 17) % 100;   // rush_yd
                s[4] = (i + w) % 2;                    // rush_td
                s[5] = (i + w * 2) % 5;                // rec
                s[6] = s[5] * 8 + (i + w) % 10;        // rec_yd
                s[8] = (i + w * 3) % 7 == 0 ? 1 : 0;   // fum_lost
            }
            case "WR" -> {
                s[5] = 2 + (i * 5 + w * 3) % 7;        // rec
                s[6] = s[5] * 11 + (i + w) % 13;       // rec_yd
                s[7] = (i + w) % 3 == 0 ? 1 : 0;       // rec_td
            }
            default -> {
                s[5] = 1 + (i * 3 + w) % 5;            // rec
                s[6] = s[5] * 9 + i % 7;               // rec_yd
                s[7] = (i * w) % 5 == 0 ? 1 : 0;       // rec_td
            }
        }
        if (i % 29 == 0 && w == 1) s[10] = 1;          // rush_2pt
        if (i % 31 == 0 && w == 2) s[12] = 1;          // st_td
        return s;
    }

    /** The fantasy points of a stat vector under the given scoring (keys in {@link NflverseSource#KEYS} order). */
    public static double points(double[] stats, java.util.Map<String, Double> scoring) {
        double t = 0;
        for (int k = 0; k < stats.length; k++) {
            Double w = scoring.get(NflverseSource.KEYS.get(k));
            if (w != null) t += w * stats[k];
        }
        return t;
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("delete from historical_game where sport = 'NFL' and season_start between ? and ?", S2, S);
        jdbc.update("delete from historical_nfl_draft_pick where player_id in"
                + " (select id from historical_player where source = 'NFLVERSE' and source_key like ?)", PREFIX + "%");
        jdbc.update("delete from historical_nfl_player_week where season between ? and ?", S2, S);
        jdbc.update("delete from historical_nfl_player_season where season between ? and ?", S2, S);
        jdbc.update("delete from historical_player where source = 'NFLVERSE' and source_key like ?", PREFIX + "%");
        jdbc.update("delete from historical_season where sport = 'NFL' and season_start between ? and ?", S2, S);
    }

    public static SeasonRow seasonRow(int season, int weekCount, int gameCount, Integer expected, int dropped) {
        return new SeasonRow("NFL", season, String.valueOf(season), "NFLVERSE", null, null, weekCount, gameCount,
                NflHistoricalSport.COUNTING, "{}", "{}", "PRIOR_SEASON_POINTS",
                OffsetDateTime.parse("2026-11-05T12:00:00Z"), expected, dropped, "synthetic-sha");
    }

    private static String id(int i) {
        return PREFIX + i;
    }

    /**
     * Seeds the three season rows, the players, their S-2/S-1 lines, the sentinel S line, four weeks of games, and the
     * rookies' NFL draft picks. The 1895 row says 60 of 64 games loaded and 3 id-less rows dropped, so the data
     * notes have something to say.
     */
    public static void seed(NflHistoricalRepository nfl, JdbcTemplate jdbc) {
        List<SeasonAgg> l2 = new ArrayList<>(), l1 = new ArrayList<>(), l0 = new ArrayList<>();
        List<WeekRow> weeks = new ArrayList<>();
        for (int i = 0; i < VETERANS + ROOKIES; i++) {
            boolean rookie = i >= VETERANS;
            String pos = position(i);
            if (!rookie) {
                if (i % 2 == 0) l2.add(agg(i, pos, S2, 15, "OLD", 20));
                l1.add(agg(i, pos, S1, 16, "T" + (i % 30), 0));
            }
            // the drafted season's own line: SENTINELS that must never reach the draft room
            double[] sentinel = new double[NflverseSource.KEYS.size()];
            sentinel[0] = 7777;
            sentinel[3] = 8888;
            sentinel[6] = 9999;
            l0.add(new SeasonAgg(id(i), name(i), pos, "ZZZ", 91, sentinel));
            for (int w = 1; w <= WEEKS; w++) {
                if (!hasGame(i, w)) continue;
                weeks.add(new WeekRow(id(i), name(i), pos, w, "1895_0" + w + "_ZZZ_YYY", "ZZZ", "YYY",
                        weekStats(i, w), null));
            }
        }
        nfl.replaceSeason(new ParsedSeason(S2, List.of(), l2, 0, 0, 0, 0, 0, "s2", 0), seasonRow(S2, 15, 0, null, 0));
        nfl.replaceSeason(new ParsedSeason(S1, List.of(), l1, 0, 0, 0, 0, 0, "s1", 0), seasonRow(S1, 16, 0, null, 0));
        nfl.replaceSeason(new ParsedSeason(S, weeks, l0, 60, WEEKS, 5, 3, 7, "synthetic-sha", 123),
                seasonRow(S, WEEKS, 60, 64, 3));

        // The rookies' NFL slots, written straight to the table: replaceDraftPicks replaces EVERYTHING, which a test
        // must never do to a database that may hold real picks.
        jdbc.update("delete from historical_nfl_draft_pick where player_id in"
                + " (select id from historical_player where source = 'NFLVERSE' and source_key like ?)", PREFIX + "%");
        int[][] slots ={{1, 5}, {1, 9}, {2, 40}, {3, 80}, {4, 120}, {5, 150}, {6, 190}, {7, 230}, {7, 240}, {7, 250}};
        for (int r = 0; r < ROOKIES; r++) {
            int i = VETERANS + r;
            jdbc.update("insert into historical_nfl_draft_pick (player_id, season, round, pick, position)"
                    + " select id, ?, ?, ?, ? from historical_player where source = 'NFLVERSE' and source_key = ?",
                    S, slots[r][0], slots[r][1], position(i), id(i));
        }
    }

    /** A season line summed from the weekly formula, so each player has his own distinct numbers. */
    private static SeasonAgg agg(int i, String pos, int season, int games, String team, int weekShift) {
        double[] total = new double[NflverseSource.KEYS.size()];
        for (int w = 1; w <= games; w++) {
            double[] s = weekStats(i, w + weekShift);
            for (int k = 0; k < total.length; k++) total[k] += s[k];
        }
        return new SeasonAgg(id(i), name(i), pos, team, games, total);
    }
}
