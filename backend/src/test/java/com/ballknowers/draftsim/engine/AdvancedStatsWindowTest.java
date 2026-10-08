package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.AdvancedStats.Counting;
import com.ballknowers.draftsim.engine.AdvancedStats.Rate;
import com.ballknowers.draftsim.engine.AdvancedStats.Window;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 022 T018: the traditional (non-advanced) parts of {@link AdvancedStats#window}, by hand-computed values. */
class AdvancedStatsWindowTest {

    private static final LocalDate D0 = LocalDate.parse("2025-11-01");

    private static Line line(int day, String team, double minutes, Map<String, Object> stats) {
        return new Line("g" + day + team, D0.plusDays(day), 1, team, "OPP", true, minutes, stats, null, null);
    }

    private static Map<String, Object> stats(double pts, double fgm, double fga, double tpm, double tpa,
                                             double ftm, double fta) {
        Map<String, Object> m = new HashMap<>();
        m.put("pts", pts);
        m.put("fgm", fgm);
        m.put("fga", fga);
        m.put("tpm", tpm);
        m.put("tpa", tpa);
        m.put("ftm", ftm);
        m.put("fta", fta);
        return m;
    }

    private static List<Line> twoGames() {
        // game 1: 30 min, 20 pts, 8/16 fg, 2/4 tp, 2/2 ft, 6 reb, 4 ast, +10
        Map<String, Object> a = stats(20, 8, 16, 2, 4, 2, 2);
        a.put("reb", 6.0);
        a.put("ast", 4.0);
        a.put("plus_minus", 10.0);
        // game 2: 10 min, 4 pts, 2/4 fg, 0/0 tp, 0/2 ft, 2 reb, no ast key at all, -4
        Map<String, Object> b = stats(4, 2, 4, 0, 0, 0, 2);
        b.put("reb", 2.0);
        b.put("plus_minus", -4.0);
        return List.of(line(1, "AAA", 30, a), line(2, "AAA", 10, b));
    }

    @Test
    void perGameTotalsAndPer36ArePooled() {
        Window w = AdvancedStats.window(twoGames(), 100);
        assertEquals(2, w.games());
        assertEquals(40.0, w.minutes(), 1e-9);
        assertEquals(20.0, w.minutesPerGame(), 1e-9);
        assertEquals(24.0, w.totals().pts(), 1e-9);
        assertEquals(12.0, w.perGame().pts(), 1e-9);
        assertEquals(8.0, w.totals().reb(), 1e-9);
        assertEquals(4.0, w.perGame().reb(), 1e-9);
        // per 36 is pooled: total * 36 / total minutes, not the mean of per-game per-36 figures
        assertEquals(24.0 * 36.0 / 40.0, w.per36().pts(), 1e-9);
        double meanOfPer36 = (20.0 * 36 / 30 + 4.0 * 36 / 10) / 2;
        assertTrue(Math.abs(meanOfPer36 - w.per36().pts()) > 1e-3);
        assertEquals(3.0, w.plusMinusPerGame(), 1e-9);
        // a stat key missing from a line counts as 0: game 2 has no ast
        assertEquals(4.0, w.totals().ast(), 1e-9);
    }

    @Test
    void shootingPercentagesArePooledRatesInPercentPoints() {
        Window w = AdvancedStats.window(twoGames(), 100);
        assertEquals(100.0 * 10 / 20, w.shooting().fgPct().value(), 1e-9);
        assertEquals(100.0 * 2 / 4, w.shooting().tpPct().value(), 1e-9);
        assertEquals(100.0 * 2 / 4, w.shooting().ftPct().value(), 1e-9);
        assertNull(w.shooting().fgPct().reason());
    }

    @Test
    void zeroAttemptsIsNoAttemptsNeverZero() {
        Map<String, Object> s = stats(2, 1, 1, 0, 0, 1, 0);
        Window w = AdvancedStats.window(List.of(line(1, "AAA", 12, s)), 100);
        Rate tp = w.shooting().tpPct();
        assertNull(tp.value());
        assertEquals(AdvancedStats.NO_ATTEMPTS, tp.reason());
        assertEquals(AdvancedStats.NO_ATTEMPTS, w.shooting().ftPct().reason());
        assertEquals(100.0, w.shooting().fgPct().value(), 1e-9);
    }

    @Test
    void datesAndSmallSampleAreSet() {
        Window w = AdvancedStats.window(twoGames(), 100);
        assertEquals(D0.plusDays(1), w.firstGameDate());
        assertEquals(D0.plusDays(2), w.lastGameDate());
        assertTrue(w.smallSample(), "40 minutes is below 100");
        assertFalse(AdvancedStats.window(twoGames(), 40).smallSample(), "at the threshold is not small");
        assertTrue(AdvancedStats.window(twoGames(), 41).smallSample());
    }

    @Test
    void anEmptyWindowSaysSoRatherThanInventingZeroes() {
        Window w = AdvancedStats.window(List.of(), 100);
        assertEquals(0, w.games());
        assertNull(w.firstGameDate());
        assertNull(w.lastGameDate());
        assertNull(w.perGame());
        assertNull(w.per36());
        assertNull(w.gameScorePerGame());
        assertNull(w.plusMinusPerGame());
        assertEquals(0.0, w.totals().pts());
        assertEquals(AdvancedStats.NO_ATTEMPTS, w.shooting().fgPct().reason());
        assertTrue(w.smallSample());
    }

    @Test
    void lastNCoversMinOfNAndGamesAndSaysWhichCount() {
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i <= 12; i++) lines.add(line(i, "AAA", 20, stats(i, 1, 2, 0, 0, 0, 0)));
        assertEquals(10, WindowKind.LAST_10.select(lines).size());
        assertEquals(5, WindowKind.LAST_5.select(lines).size());
        assertEquals(12, WindowKind.SEASON.select(lines).size());
        Window last5 = AdvancedStats.window(WindowKind.LAST_5.select(lines), 100);
        assertEquals(5, last5.games());
        assertEquals(D0.plusDays(8), last5.firstGameDate());
        assertEquals(D0.plusDays(12), last5.lastGameDate());
        assertEquals((8 + 9 + 10 + 11 + 12) / 5.0, last5.perGame().pts(), 1e-9);

        List<Line> few = lines.subList(0, 3);
        assertEquals(3, WindowKind.LAST_10.select(few).size(), "min(N, games)");
        assertEquals(3, AdvancedStats.window(WindowKind.LAST_10.select(few), 100).games());
    }

    @Test
    void gameScoreFollowsTheFormula() {
        Map<String, Object> s = new HashMap<>();
        s.put("pts", 20.0); s.put("fgm", 8.0); s.put("fga", 16.0); s.put("ftm", 2.0); s.put("fta", 4.0);
        s.put("oreb", 2.0); s.put("dreb", 4.0); s.put("stl", 1.0); s.put("ast", 5.0); s.put("blk", 1.0);
        s.put("pf", 3.0); s.put("to", 2.0);
        // 20 + 3.2 - 11.2 - 0.8 + 1.4 + 1.2 + 1 + 3.5 + 0.7 - 1.2 - 2 = 15.8
        assertEquals(15.8, AdvancedStats.gameScore(line(1, "AAA", 30, s)), 1e-9);
        Window w = AdvancedStats.window(List.of(line(1, "AAA", 30, s)), 100);
        assertEquals(15.8, w.gameScorePerGame(), 1e-9);
    }

    // ------------------------------------------------------------------ teamGamesMissed (F10)

    private static TeamGame tg(String code, int day) {
        return new TeamGame(code, "g" + day + code, D0.plusDays(day), "OPP", Map.of());
    }

    private static List<TeamGame> games(String code, int from, int to) {
        List<TeamGame> l = new ArrayList<>();
        for (int d = from; d <= to; d++) l.add(tg(code, d));
        return l;
    }

    @Test
    void teamGamesMissedForATradedPlayerCountsOnlyGamesWhileOnEachTeam() {
        Map<String, List<TeamGame>> teamGames = new HashMap<>();
        teamGames.put("AAA", games("AAA", 1, 20));      // 20 games all season
        teamGames.put("BBB", games("BBB", 1, 20));
        List<Line> lines = new ArrayList<>();
        // with AAA from day 3..8: plays 3,4,6,8 (misses 5 and 7); days 1-2 and 9+ are not missed
        for (int d : new int[] {3, 4, 6, 8}) lines.add(line(d, "AAA", 20, Map.of("pts", 1.0)));
        // traded; with BBB from day 12..16: plays 12,13,16 (misses 14,15); days 9-11 and 17-20 are not missed
        for (int d : new int[] {12, 13, 16}) lines.add(line(d, "BBB", 20, Map.of("pts", 1.0)));
        assertEquals(2 + 2, AdvancedStats.teamGamesMissed(lines, teamGames, List.of()));
    }

    @Test
    void aMidSeasonSigningDoesNotOweTheGamesBeforeHeJoined() {
        Map<String, List<TeamGame>> teamGames = new HashMap<>();
        teamGames.put("AAA", games("AAA", 1, 30));
        List<Line> lines = new ArrayList<>();
        for (int d = 21; d <= 30; d++) lines.add(line(d, "AAA", 20, Map.of("pts", 1.0)));
        assertEquals(0, AdvancedStats.teamGamesMissed(lines, teamGames, List.of()));
        assertEquals(0, AdvancedStats.teamGamesMissed(List.of(), teamGames, List.of()));
    }

    @Test
    void aGapInTheMiddleIsMissed() {
        Map<String, List<TeamGame>> teamGames = new HashMap<>();
        teamGames.put("AAA", games("AAA", 1, 10));
        List<Line> lines = new ArrayList<>();
        for (int d : new int[] {1, 2, 10}) lines.add(line(d, "AAA", 20, Map.of("pts", 1.0)));
        assertEquals(7, AdvancedStats.teamGamesMissed(lines, teamGames, List.of()));
        Counting c = AdvancedStats.window(lines, 100).totals();
        assertEquals(3.0, c.pts(), 1e-9);
    }

    private static PlayerAbsenceRepository.Row absent(String code, int day, String team, String basis) {
        return new PlayerAbsenceRepository.Row(Sport.NBA, 2025, 1, "p1", day < 0 ? null : "g" + day + code,
                day < 0 ? null : D0.plusDays(day), team, basis);
    }

    private static List<Line> played(String team, int... days) {
        List<Line> l = new ArrayList<>();
        for (int d : days) l.add(line(d, team, 20, Map.of("pts", 1.0)));
        return l;
    }

    @Test
    void aSeasonEndingInjuryCountsTheGamesAfterHisLastAppearanceThatHaveAbsenceRows() {
        Map<String, List<TeamGame>> teamGames = Map.of("AAA", games("AAA", 1, 20));
        List<Line> lines = played("AAA", 1, 2, 4, 5);    // misses 3 inside the span
        List<PlayerAbsenceRepository.Row> abs = new ArrayList<>();
        for (int d = 6; d <= 20; d++) abs.add(absent("AAA", d, "AAA", "ENTRY_WITHOUT_PLAY"));
        assertEquals(1 + 15, AdvancedStats.teamGamesMissed(lines, teamGames, abs));
    }

    @Test
    void aWaivedPlayerWithNoAbsenceRowsAfterHisLastGameOwesNothingAfterIt() {
        Map<String, List<TeamGame>> teamGames = Map.of("AAA", games("AAA", 1, 20));
        assertEquals(1, AdvancedStats.teamGamesMissed(played("AAA", 1, 2, 4, 5), teamGames, List.of()));
    }

    @Test
    void onlyGameLevelEntryWithoutPlayRowsOnTheLastTeamCountAfterTheLastGame() {
        Map<String, List<TeamGame>> teamGames = Map.of("AAA", games("AAA", 1, 10));
        List<Line> lines = played("AAA", 1, 2);
        List<PlayerAbsenceRepository.Row> abs = List.of(
                absent("AAA", 3, "AAA", "ENTRY_WITHOUT_PLAY"),           // counts
                absent("AAA", 4, null, "ENTRY_WITHOUT_PLAY"),            // no team on the row: counts
                absent("AAA", 5, "ZZZ", "ENTRY_WITHOUT_PLAY"),           // another team's code: ignored
                absent("AAA", 6, "AAA", "TEAM_PLAYED_NO_ENTRY"),         // other basis: ignored
                absent("AAA", -1, "AAA", "ENTRY_WITHOUT_PLAY"));         // week-level, no game id: ignored
        assertEquals(2, AdvancedStats.teamGamesMissed(lines, teamGames, abs));
    }

    @Test
    void absenceRowsAfterTheLastGameOnlyCountForHisLastTeam() {
        Map<String, List<TeamGame>> teamGames = new HashMap<>();
        teamGames.put("AAA", games("AAA", 1, 20));
        teamGames.put("BBB", games("BBB", 1, 20));
        List<Line> lines = played("AAA", 1, 2, 3);
        lines.addAll(played("BBB", 10, 11));                              // traded to BBB, last team
        List<PlayerAbsenceRepository.Row> abs = List.of(
                absent("AAA", 15, "AAA", "ENTRY_WITHOUT_PLAY"),           // AAA is not his last team: ignored
                absent("BBB", 15, "BBB", "ENTRY_WITHOUT_PLAY"),
                absent("BBB", 16, "BBB", "ENTRY_WITHOUT_PLAY"));
        assertEquals(2, AdvancedStats.teamGamesMissed(lines, teamGames, abs));
    }

    // ------------------------------------------------------------------ advanced rates (spec 022 T035, research R5)

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return out;
    }

    private static TeamGame tg(String code, int day, Map<String, Object> stats) {
        return new TeamGame(code, "g" + day, D0.plusDays(day), "X", stats);
    }

    private static Line adv(int day, double minutes, Map<String, Object> stats, Map<String, Object> team,
                            Map<String, Object> opp) {
        return new Line("g" + day, D0.plusDays(day), 1, "AAA", "OPP", true, minutes, stats,
                team == null ? null : tg("AAA", day, team), opp == null ? null : tg("OPP", day, opp));
    }

    /** Game 1 regulation (240 team minutes), game 2 one overtime (265). Expected values: hand arithmetic in comments. */
    private static List<Line> twoRealGames() {
        Map<String, Object> p1 = m("pts", 20, "fgm", 8, "fga", 15, "tpm", 2, "tpa", 5, "ftm", 2, "fta", 5, "to", 3,
                "ast", 6, "oreb", 2, "dreb", 5, "reb", 7, "stl", 2, "blk", 1);
        Map<String, Object> t1 = m("sp", 14400, "fga", 90, "fta", 20, "to", 12, "fgm", 40, "oreb", 10, "dreb", 30,
                "reb", 40, "tpa", 30);
        Map<String, Object> o1 = m("fga", 88, "fta", 18, "to", 14, "fgm", 38, "oreb", 8, "dreb", 32, "reb", 40,
                "tpa", 28);
        Map<String, Object> p2 = m("pts", 30, "fgm", 11, "fga", 25, "tpm", 3, "tpa", 10, "ftm", 5, "fta", 6, "to", 5,
                "ast", 4, "oreb", 1, "dreb", 9, "reb", 10, "stl", 1, "blk", 3);
        Map<String, Object> t2 = m("sp", 15900, "fga", 100, "fta", 30, "to", 10, "fgm", 45, "oreb", 12, "dreb", 35,
                "reb", 47, "tpa", 40);
        Map<String, Object> o2 = m("fga", 95, "fta", 25, "to", 16, "fgm", 41, "oreb", 11, "dreb", 33, "reb", 44,
                "tpa", 35);
        return List.of(adv(1, 30, p1, t1, o1), adv(2, 40, p2, t2, o2));
    }

    @Test
    void everyAdvancedRateIsThePooledHandComputedValue() {
        AdvancedStats.Advanced a = AdvancedStats.window(twoRealGames(), 100).advanced();
        // PTS 50, FGA 40, FTA 11, FGM 19, 3PM 5, 3PA 15, TOV 8
        assertEquals(55.753791257805524, a.ts().value(), 1e-9);   // 100 x 50 / (2 x (40 + 0.44 x 11)) = 5000 / 89.68
        assertEquals(53.75, a.efg().value(), 1e-9);                // 100 x (19 + 2.5) / 40
        assertEquals(27.5, a.ftr().value(), 1e-9);                 // 100 x 11 / 40: percent points
        assertEquals(37.5, a.tpar().value(), 1e-9);                // 100 x 15 / 40
        assertEquals(15.140045420136259, a.tovPct().value(), 1e-9); // 100 x 8 / (40 + 4.84 + 8)
        // 70 of the 101.0 player-minute slots: (30 + 40) / (48 + 53)
        assertEquals(69.3069306930693, a.minutesShare().value(), 1e-9);
        assertEquals(25.02360717658168, a.astPct().value(), 1e-9); // 100 x 10 / (30/48 x 40 - 8 + 40/53 x 45 - 11)
        assertEquals(4.869281045751634, a.orbPct().value(), 1e-9);  // 100 x (2 x 48 + 1 x 53) / (30 x 42 + 40 x 45)
        assertEquals(24.06040268456376, a.drbPct().value(), 1e-9);  // 100 x (5 x 48 + 9 x 53) / (30 x 62 + 40 x 68)
        assertEquals(14.33774834437086, a.trbPct().value(), 1e-9);  // 100 x (7 x 48 + 10 x 53) / (30 x 80 + 40 x 91)
        // Poss = 97.5994 (game 1), 106.7449 (game 2): 100 x (2 x 48 + 1 x 53) / (30 x 97.5994 + 40 x 106.7449)
        assertEquals(2.070084041212781, a.stlPct().value(), 1e-9);
        assertEquals(4.928571428571429, a.blkPct().value(), 1e-9); // 100 x (1 x 48 + 3 x 53) / (30 x 60 + 40 x 60)
    }

    @Test
    void usageIsExactlyTheOneImplementation() {
        List<Line> games = twoRealGames();
        assertEquals(AdvancedStats.usage(games), AdvancedStats.window(games, 100).advanced().usg());
        assertEquals(32.71352399418323, AdvancedStats.window(games, 100).advanced().usg().value(), 1e-9);
    }

    @Test
    void ratesArePooledNeverAveragedPerGame() {
        List<Line> games = twoRealGames();
        double game1 = AdvancedStats.window(games.subList(0, 1), 100).advanced().ts().value();
        double game2 = AdvancedStats.window(games.subList(1, 2), 100).advanced().ts().value();
        double meanOfRates = (game1 + game2) / 2;                   // 56.204, which a pooled rate must not be
        double pooled = AdvancedStats.window(games, 100).advanced().ts().value();
        assertEquals(55.753791257805524, pooled, 1e-9);
        assertTrue(Math.abs(meanOfRates - pooled) > 0.4);
        // the same for a team-relative rate: the mean of per-game TRB% is not the pooled one
        double t1 = AdvancedStats.window(games.subList(0, 1), 100).advanced().trbPct().value();
        double t2 = AdvancedStats.window(games.subList(1, 2), 100).advanced().trbPct().value();
        assertTrue(Math.abs((t1 + t2) / 2 - AdvancedStats.window(games, 100).advanced().trbPct().value()) > 0.01);
    }

    @Test
    void anOvertimeGameUsesTheTeamMinutesOfThatGame() {
        Map<String, Object> p = m("fga", 10, "fta", 0, "to", 0, "reb", 10);
        Map<String, Object> team = m("sp", 15900, "fga", 100, "reb", 50, "fgm", 40);
        Map<String, Object> opp = m("fga", 90, "reb", 50, "tpa", 30);
        // 40 minutes of 265 / 5 = 53: 100 x 40 / 53
        assertEquals(100.0 * 40 / 53, AdvancedStats.advanced(List.of(adv(1, 40, p, team, opp))).minutesShare().value(), 1e-9);
        // the same minutes in regulation: 100 x 40 / 48
        Map<String, Object> reg = m("sp", 14400, "fga", 100, "reb", 50, "fgm", 40);
        assertEquals(100.0 * 40 / 48, AdvancedStats.advanced(List.of(adv(1, 40, p, reg, opp))).minutesShare().value(), 1e-9);
        // TRB%: 100 x 10 x 53 / (40 x 100)
        assertEquals(100.0 * 10 * 53 / (40 * 100), AdvancedStats.advanced(List.of(adv(1, 40, p, team, opp))).trbPct().value(), 1e-9);
    }

    @Test
    void zeroDenominatorsGiveTheirReasonNeverZero() {
        // box-only rates: no attempts at all
        Map<String, Object> idle = m("pts", 0, "fga", 0, "fta", 0, "to", 0, "reb", 2);
        Map<String, Object> team = m("sp", 14400, "fga", 80, "fta", 20, "to", 12, "fgm", 30, "reb", 40, "oreb", 8, "dreb", 32);
        Map<String, Object> opp = m("fga", 80, "tpa", 25, "reb", 40, "oreb", 8, "dreb", 32);
        AdvancedStats.Advanced a = AdvancedStats.advanced(List.of(adv(1, 10, idle, team, opp)));
        for (Rate r : List.of(a.ts(), a.efg(), a.ftr(), a.tpar(), a.tovPct())) {
            assertNull(r.value());
            assertEquals("NO_ATTEMPTS", r.reason());
        }
        // an empty window: shot-based are NO_ATTEMPTS, team-relative are NO_TEAM_ROW
        AdvancedStats.Advanced empty = AdvancedStats.advanced(List.of());
        assertEquals("NO_ATTEMPTS", empty.ts().reason());
        assertEquals("NO_TEAM_ROW", empty.usg().reason());
        assertEquals("NO_TEAM_ROW", empty.minutesShare().reason());
        assertEquals("NO_TEAM_ROW", empty.blkPct().reason());
        // team rows but no minutes
        AdvancedStats.Advanced noMin = AdvancedStats.advanced(List.of(adv(1, 0, idle, team, opp)));
        assertEquals("NO_MINUTES", noMin.minutesShare().reason());
        assertEquals("NO_MINUTES", noMin.astPct().reason());
        assertEquals("NO_MINUTES", noMin.trbPct().reason());
        assertEquals("NO_MINUTES", noMin.usg().reason());
        // minutes, but a zero denominator: nobody rebounded, the opponent took only threes, no team FGM to assist
        Map<String, Object> deadTeam = m("sp", 14400);
        Map<String, Object> threesOnly = m("fga", 20, "tpa", 20);
        AdvancedStats.Advanced zeroDen = AdvancedStats.advanced(List.of(adv(1, 10, idle, deadTeam, threesOnly)));
        assertEquals("NO_ATTEMPTS", zeroDen.usg().reason());
        assertEquals("NO_ATTEMPTS", zeroDen.orbPct().reason());
        assertEquals("NO_ATTEMPTS", zeroDen.drbPct().reason());
        assertEquals("NO_ATTEMPTS", zeroDen.trbPct().reason());
        assertEquals("NO_ATTEMPTS", zeroDen.blkPct().reason());
        assertEquals("NO_ATTEMPTS", zeroDen.astPct().reason());   // 10/48 x 0 - 0 = 0
        assertEquals(100.0 * 10 / 48, zeroDen.minutesShare().value(), 1e-9);   // an unrelated zero denominator leaves it alone
    }

    @Test
    void aGameWithoutATeamRowIsLeftOutOfTheTeamRelativeRates() {
        List<Line> both = new ArrayList<>(twoRealGames());
        Line noTeam = adv(3, 25, m("pts", 40, "fga", 20, "fta", 10, "to", 1, "reb", 9, "ast", 9, "stl", 3, "blk", 3), null, null);
        both.add(noTeam);
        AdvancedStats.Advanced withExtra = AdvancedStats.advanced(both);
        AdvancedStats.Advanced base = AdvancedStats.advanced(twoRealGames());
        // team-relative: the unmatched game contributes nothing, not even minutes
        assertEquals(base.minutesShare().value(), withExtra.minutesShare().value(), 1e-9);
        assertEquals(base.usg().value(), withExtra.usg().value(), 1e-9);
        assertEquals(base.trbPct().value(), withExtra.trbPct().value(), 1e-9);
        assertEquals(base.stlPct().value(), withExtra.stlPct().value(), 1e-9);
        // box-only: it counts
        assertNotEquals(base.ts().value(), withExtra.ts().value());
        // a window of only such games: NO_TEAM_ROW for every team-relative rate, a value for the box-only ones
        AdvancedStats.Advanced only = AdvancedStats.advanced(List.of(noTeam));
        assertEquals("NO_TEAM_ROW", only.usg().reason());
        assertEquals("NO_TEAM_ROW", only.minutesShare().reason());
        assertEquals("NO_TEAM_ROW", only.astPct().reason());
        assertEquals("NO_TEAM_ROW", only.orbPct().reason());
        assertEquals("NO_TEAM_ROW", only.drbPct().reason());
        assertEquals("NO_TEAM_ROW", only.trbPct().reason());
        assertEquals("NO_TEAM_ROW", only.stlPct().reason());
        assertEquals("NO_TEAM_ROW", only.blkPct().reason());
        assertEquals(100.0 * 40 / (2 * (20 + 4.4)), only.ts().value(), 1e-9);
    }

    @Test
    void aMissingOpponentRowOnlyAffectsTheRatesThatNeedIt() {
        Map<String, Object> p = m("fga", 10, "fgm", 5, "ast", 3, "reb", 6, "to", 1);
        Map<String, Object> team = m("sp", 14400, "fga", 80, "fta", 10, "to", 10, "fgm", 35, "reb", 40);
        AdvancedStats.Advanced a = AdvancedStats.advanced(List.of(adv(1, 30, p, team, null)));
        assertNotNull(a.usg().value());
        assertNotNull(a.minutesShare().value());
        assertNotNull(a.astPct().value());
        assertEquals("NO_TEAM_ROW", a.trbPct().reason());
        assertEquals("NO_TEAM_ROW", a.stlPct().reason());
        assertEquals("NO_TEAM_ROW", a.blkPct().reason());
    }

    @Test
    void theWindowCarriesTheAdvancedRatesForEveryWindowIncludingAnEmptyOne() {
        Window empty = AdvancedStats.window(List.of(), 100);
        assertNotNull(empty.advanced());
        assertEquals("NO_ATTEMPTS", empty.advanced().ts().reason());
        List<Line> games = twoRealGames();
        // LAST_5 of two games is both games
        Window last5 = AdvancedStats.window(WindowKind.LAST_5.select(games), 100);
        assertEquals(AdvancedStats.window(games, 100).advanced(), last5.advanced());
        assertEquals(AdvancedStats.ADVANCED_KEYS, List.copyOf(last5.advanced().byKey().keySet()));
    }
}
