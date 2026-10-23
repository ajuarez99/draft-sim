package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.ExpectedWinsService.Game;
import com.ballknowers.draftsim.engine.ExpectedWinsService.ScheduleSwap;
import com.ballknowers.draftsim.engine.ExpectedWinsService.WinLossTie;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 031: the schedule-swap matrix as a pure function. The 4-team, 3-week fixture is the
 * hand-worked one from specs/031-schedule-swap/plan-review.md (a round robin, one real tie).
 */
class ScheduleSwapTest {

    private static final List<Integer> ORDER = List.of(1, 2, 3, 4);

    /** Schedules (opponent by week): 1 -> 2,3,4  2 -> 1,4,3  3 -> 4,1,2  4 -> 3,2,1. */
    private static List<Game> fixture() {
        return List.of(
                new Game(1, 1, 100, 2, 90), new Game(1, 3, 80, 4, 110),
                new Game(2, 1, 70, 3, 95), new Game(2, 2, 120, 4, 60),
                new Game(3, 1, 105, 4, 100), new Game(3, 2, 85, 3, 85));
    }

    private static WinLossTie r(int w, int l, int t) {
        return new WinLossTie(w, l, t);
    }

    private static double winsOf(WinLossTie c) {
        return c.wins() + c.ties() / 2.0;
    }

    @Test
    void handWorkedFourByThreeGrid() {
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(fixture(), ORDER);
        assertEquals(ORDER, s.rosterIds());
        List<List<WinLossTie>> expected = List.of(
                List.of(r(2, 1, 0), r(3, 0, 0), r(1, 2, 0), r(2, 1, 0)),
                List.of(r(1, 2, 0), r(1, 1, 1), r(1, 1, 1), r(2, 1, 0)),
                List.of(r(1, 2, 0), r(1, 1, 1), r(1, 1, 1), r(0, 3, 0)),
                List.of(r(1, 2, 0), r(2, 1, 0), r(2, 1, 0), r(1, 2, 0)));
        assertEquals(expected, s.cells());
    }

    @Test
    void difficultyIsTheMeanWinRateOnEachSchedule() {
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(fixture(), ORDER);
        // mean wins 1.25 / 2.00 / 1.50 / 1.25 over 3 games each; schedules 1 and 4 tie as hardest
        assertEquals(List.of(0.417, 0.667, 0.5, 0.417), s.difficultyWinRate());
    }

    /** Spec scenario 2 / FR-002: where S's opponent is T itself, T plays S that week. */
    @Test
    void selfPairingRuleTPlaysSWhenSIsFacingT() {
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(fixture(), ORDER);
        // Team 1 on team 2's schedule: week 1 team 2 faced team 1, so team 1 plays team 2
        // (100 v 90, a win); then 70 v 60 (win) and 105 v 85 (win).
        assertEquals(r(3, 0, 0), s.cells().get(0).get(1));
        // Team 2 on team 3's schedule: week 3 team 3 faced team 2, so team 2 plays team 3 (85 v 85, tie).
        assertEquals(r(1, 1, 1), s.cells().get(1).get(2));
    }

    @Test
    void diagonalIsTheRealRecordCountedGameByGame() {
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(fixture(), ORDER);
        assertEquals(List.of(r(2, 1, 0), r(1, 1, 1), r(1, 1, 1), r(1, 2, 0)),
                List.of(s.cells().get(0).get(0), s.cells().get(1).get(1),
                        s.cells().get(2).get(2), s.cells().get(3).get(3)));
        Map<Integer, Double> actual = ExpectedWinsService.actualWins(fixture());
        for (int i = 0; i < 4; i++) assertEquals(actual.get(ORDER.get(i)), winsOf(s.cells().get(i).get(i)), 1e-9);
    }

    /** F1 / F7: no byes, so the other N-1 schedules average to expected wins and difficulty to 0.5. */
    @Test
    void noByeIdentities() {
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(fixture(), ORDER);
        Map<Integer, Double> expectedWins = ExpectedWinsService.expectedWins(fixture());
        int n = ORDER.size();
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) if (j != i) sum += winsOf(s.cells().get(i).get(j));
            assertEquals(expectedWins.get(ORDER.get(i)), sum / (n - 1), 1e-9, "roster " + ORDER.get(i));
        }
        double mean = s.difficultyWinRate().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertEquals(0.5, mean, 0.002);
    }

    /** Scenario 3: a roster with no game gets no row and no column. */
    @Test
    void aRosterThatNeverPlayedHasNoRowOrColumn() {
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(fixture(), List.of(1, 2, 3, 4, 5));
        assertEquals(ORDER, s.rosterIds());
        assertEquals(4, s.cells().size());
        s.cells().forEach(row -> assertEquals(4, row.size()));
        assertEquals(ExpectedWinsService.scheduleSwap(fixture(), ORDER), s);
    }

    /**
     * Scenarios 3 and 4: week 2's 2 v 4 game is dropped. Columns 2 and 4 lose week 2 (not played in
     * that cell); rows 2 and 4 have no week-2 score, so they skip it in every column (not a loss).
     */
    @Test
    void byeColumnWeekIsNotPlayedAndMissingRowScoreIsSkippedNotLost() {
        List<Game> games = new ArrayList<>(fixture());
        games.remove(new Game(2, 2, 120, 4, 60));
        ScheduleSwap s = ExpectedWinsService.scheduleSwap(games, ORDER);

        int[] columnGames = {3, 2, 3, 2}; // weeks each roster has a game
        int[] rowMissing = {0, 1, 0, 1};  // weeks a row's roster has no score, within weeks the column played
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                WinLossTie c = s.cells().get(i).get(j);
                int total = c.wins() + c.losses() + c.ties();
                // row i misses week 2 only; that week is in column j's games for j = 1 and 3 (rosters 1, 3)
                boolean colHasWeek2 = (j == 0 || j == 2);
                int expected = columnGames[j] - ((colHasWeek2 && rowMissing[i] == 1) ? 1 : 0);
                assertEquals(expected, total, "cell " + i + "," + j);
            }
        }
        // spot check: team 2 on team 1's schedule (opp 2, 3, 4): week 1 vs... team 1's week-1 opp is
        // team 2 = T, so T plays 1: 90 v 100 L; week 2 skipped; week 3 opp 4: 85 v 100 L.
        assertEquals(r(0, 2, 0), s.cells().get(1).get(0));
        // the diagonal is still the per-game record
        assertEquals(r(0, 1, 1), s.cells().get(1).get(1));
        assertEquals(r(1, 1, 0), s.cells().get(3).get(3));
        // difficulty is a rate, so a bye column is comparable: column 2 has 2 games, not 3
        // Hand-worked: mean over rows of (W + T/2) / games. Column 1: rows 2/3, 0/2, 1/3, 1/2 -> 0.375.
        // Column 2: 1, 1/4, 1/4, 1 -> 0.625. Column 3: 1/3, 1/4, 1/2, 1 -> 0.521. Column 4: 1, 1/2, 0, 1/2 -> 0.5.
        assertEquals(List.of(0.375, 0.625, 0.521, 0.5), s.difficultyWinRate());
    }

    @Test
    void aRosterTwiceInOneWeekThrows() {
        List<Game> games = List.of(new Game(1, 1, 100, 2, 90), new Game(1, 1, 80, 3, 70));
        assertThrows(IllegalStateException.class, () -> ExpectedWinsService.scheduleSwap(games, List.of(1, 2, 3)));
    }

    @Test
    void fewerThanTwoRostersIsNull() {
        assertNull(ExpectedWinsService.scheduleSwap(List.of(), List.of()));
        assertNull(ExpectedWinsService.scheduleSwap(fixture(), List.of(1)));
    }

    /** Review B2: a duplicate roster-week must not break compute() for the other callers (or the page). */
    @Test
    void duplicateRosterWeekDropsTheMatrixInsteadOfFailingTheResult() {
        var leagues = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.LeagueRepository.class);
        var matchups = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.LeagueMatchupRepository.class);
        var rosterSeasons = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.RosterSeasonRepository.class);
        var members = org.mockito.Mockito.mock(com.ballknowers.draftsim.store.LeagueMemberRepository.class);
        var seasons = org.mockito.Mockito.mock(LeagueSeasonResolver.class);
        var scoredWeeks = org.mockito.Mockito.mock(ScoredWeeks.class);
        var league = new com.ballknowers.draftsim.store.LeagueRepository.LeagueRow(7L,
                com.ballknowers.draftsim.domain.Sport.NFL, "L7", "x", 2025, 4, List.of(), 1.0, null, "complete");
        org.mockito.Mockito.when(seasons.resolve(org.mockito.ArgumentMatchers.eq("L7"), org.mockito.ArgumentMatchers.eq(LeagueSeasonResolver.Rule.PLAYED_WEEKS))).thenReturn(java.util.Optional.of(
                new LeagueSeasonResolver.Resolved(league, null)));
        org.mockito.Mockito.when(scoredWeeks.of(7L)).thenReturn(new ScoredWeeks.Snapshot(1, 1, java.util.Set.of(1)));
        org.mockito.Mockito.when(leagues.playoffFormat(7L)).thenReturn(java.util.Optional.empty());
        // roster 1 sits in two pairings in week 1 (three rosters on one matchup_id)
        org.mockito.Mockito.when(matchups.pairedWithScores(org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame(2025, 1,
                        1, null, null, null, java.math.BigDecimal.valueOf(100),
                        2, null, null, null, java.math.BigDecimal.valueOf(90)),
                new com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame(2025, 1,
                        1, null, null, null, java.math.BigDecimal.valueOf(100),
                        3, null, null, null, java.math.BigDecimal.valueOf(80))));
        var service = new ExpectedWinsService(leagues, matchups, rosterSeasons, members, seasons, scoredWeeks);

        ExpectedWinsService.Result r = service.forLeagueRegularSeason("L7").orElseThrow();
        assertTrue(r.available());
        assertNull(r.scheduleSwap());
        assertFalse(r.teams().isEmpty());
        // the explicit-window path never builds the matrix at all
        assertNull(service.forLeague("L7", com.ballknowers.draftsim.store.WeekBound.ALL_WEEKS).orElseThrow().scheduleSwap());
    }
}
