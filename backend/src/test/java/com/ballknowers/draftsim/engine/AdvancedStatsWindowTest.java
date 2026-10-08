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
}
