package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.ExpectedWinsService.Game;
import com.ballknowers.draftsim.engine.ExpectedWinsService.SwingWeek;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The all-play model is a pure function of the played games, so it is pinned
 * here directly -- the split {@code LeagueAnalysisServiceTest} describes, with
 * the repository walk left to an integration test.
 *
 * <p>specs/004-ffwrapped-feature-parity US3.
 */
class ExpectedWinsServiceTest {

    /** Six teams, one week, scores 60/50/40/30/20/10. */
    private static List<Game> oneWeekOfSix() {
        return List.of(
                new Game(1, 1, 60, 2, 50),
                new Game(1, 3, 40, 4, 30),
                new Game(1, 5, 20, 6, 10));
    }

    /**
     * US3.1. The top scorer beat all five others, so 5/5. The bottom scorer beat
     * nobody, so 0/5. The third-highest beat three of five.
     */
    @Test
    void aTeamsWeeklyShareIsTheFractionOfOthersItOutscored() {
        Map<Integer, Double> exp = ExpectedWinsService.expectedWins(oneWeekOfSix());
        assertEquals(1.0, exp.get(1), 1e-9);
        assertEquals(0.8, exp.get(2), 1e-9);
        assertEquals(0.6, exp.get(3), 1e-9);
        assertEquals(0.4, exp.get(4), 1e-9);
        assertEquals(0.2, exp.get(5), 1e-9);
        assertEquals(0.0, exp.get(6), 1e-9);
    }

    /**
     * SC-004 / US3.2. The check that PROVES the model rather than merely
     * exercising it: each of the N/2 games hands out exactly one win, and the
     * all-play shares sum to N/2 as well.
     */
    @Test
    void expectedWinsSumToActualWinsForOneWeek() {
        List<Game> games = oneWeekOfSix();
        double exp = ExpectedWinsService.expectedWins(games).values().stream()
                .mapToDouble(Double::doubleValue).sum();
        double act = ExpectedWinsService.actualWins(games).values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertEquals(act, exp, 1e-9);
        assertEquals(3.0, act, 1e-9, "three games, three wins");
    }

    /**
     * The same invariant over a randomised season, so it is a property rather
     * than a fixture that happens to balance. Twelve teams, six pairings a
     * week, fourteen weeks, arbitrary scores.
     */
    @Test
    void expectedWinsSumToActualWinsOverARandomisedSeason() {
        RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
        List<Game> games = new java.util.ArrayList<>();
        for (int week = 1; week <= 14; week++) {
            List<Integer> rosters = new java.util.ArrayList<>(
                    List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
            java.util.Collections.shuffle(rosters, new java.util.Random(rng.nextLong()));
            for (int i = 0; i < rosters.size(); i += 2) {
                games.add(new Game(week, rosters.get(i), 60 + rng.nextDouble() * 120,
                        rosters.get(i + 1), 60 + rng.nextDouble() * 120));
            }
        }
        double exp = ExpectedWinsService.expectedWins(games).values().stream()
                .mapToDouble(Double::doubleValue).sum();
        double act = ExpectedWinsService.actualWins(games).values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertEquals(act, exp, 1e-9, "expected wins must conserve against real wins");
        assertEquals(84.0, act, 1e-9, "14 weeks x 6 games");
    }

    /** A tie is half a win on both sides, and the invariant still holds. */
    @Test
    void aTieIsHalfAWinOnBothSidesAndStillConserves() {
        List<Game> games = List.of(
                new Game(1, 1, 100, 2, 100),
                new Game(1, 3, 90, 4, 80));
        Map<Integer, Double> act = ExpectedWinsService.actualWins(games);
        assertEquals(0.5, act.get(1), 1e-9);
        assertEquals(0.5, act.get(2), 1e-9);
        assertEquals(1.0, act.get(3), 1e-9);
        assertEquals(0.0, act.get(4), 1e-9);

        double exp = ExpectedWinsService.expectedWins(games).values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertEquals(2.0, exp, 1e-9);
    }

    /**
     * US3.3. Positive means a harder schedule. Roster 1 faces only the
     * high-scoring roster 2; roster 3 faces only the low-scoring roster 4.
     */
    @Test
    void strengthOfScheduleIsOpponentPpgAgainstTheLeagueAverage() {
        List<Game> games = List.of(
                new Game(1, 1, 100, 2, 150),
                new Game(1, 3, 100, 4, 50));
        Map<Integer, Double> sos = ExpectedWinsService.strengthOfSchedule(games);
        assertTrue(sos.get(1) > 0, "faced the 150-point team: harder than average");
        assertTrue(sos.get(3) < 0, "faced the 50-point team: easier than average");
        assertEquals(0.0, sos.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9,
                "schedule difficulty is zero-sum across a league that plays only itself");
    }

    /**
     * US3.4. A win from the bottom half of the week's scores is a swing week;
     * a win from the top half is not. Roster 5 wins while scoring 5th of 6.
     */
    @Test
    void aWinFromTheBottomHalfOfTheWeekIsASwingWeek() {
        // Scores: r1 60, r2 50, r3 40, r4 30, r5 20, r6 10 -- r5 beats r6 at rank 5.
        List<SwingWeek> swings = ExpectedWinsService.swingWeeks(
                5, oneWeekOfSix(), Map.of(6, "Sixth Place"));
        assertEquals(1, swings.size());
        assertTrue(swings.getFirst().won());
        assertEquals(5, swings.getFirst().weeklyRank());
        assertEquals("Sixth Place", swings.getFirst().opponent());
    }

    /** A loss from the top half is the mirror case, and is also a swing week. */
    @Test
    void aLossFromTheTopHalfOfTheWeekIsASwingWeek() {
        // r2 scores 50 (2nd of 6) and loses to r1.
        List<SwingWeek> swings = ExpectedWinsService.swingWeeks(
                2, oneWeekOfSix(), Map.of(1, "Top Scorer"));
        assertEquals(1, swings.size());
        assertFalse(swings.getFirst().won());
        assertEquals(2, swings.getFirst().weeklyRank());
    }

    /** The top scorer winning is exactly what should happen: not luck. */
    @Test
    void winningAsTheTopScorerIsNotASwingWeek() {
        assertTrue(ExpectedWinsService.swingWeeks(1, oneWeekOfSix(), Map.of()).isEmpty());
    }

    /**
     * US3.5 / FR-004. Nothing in this model touches a position, a lineup or a
     * projection, so a basketball league computes identically -- the same games
     * with basketball-sized scores give the same shares.
     */
    @Test
    void basketballScoresComputeIdentically() {
        List<Game> nba = List.of(
                new Game(1, 1, 260, 2, 240),
                new Game(1, 3, 220, 4, 200));
        Map<Integer, Double> exp = ExpectedWinsService.expectedWins(nba);
        assertEquals(1.0, exp.get(1), 1e-9);
        assertEquals(2.0, exp.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertEquals(
                ExpectedWinsService.actualWins(nba).values().stream().mapToDouble(Double::doubleValue).sum(),
                exp.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    /** A week with one roster and no opponent contributes nothing, not a divide by zero. */
    @Test
    void aWeekWithNothingToCompareAgainstContributesNothing() {
        assertTrue(ExpectedWinsService.expectedWins(List.of()).isEmpty());
        assertEquals(0.0, ExpectedWinsService.leaguePpg(List.of()), 1e-9);
    }
}
