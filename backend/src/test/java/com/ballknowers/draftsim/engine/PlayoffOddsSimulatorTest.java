package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * claude/playoff-odds.md acceptance criteria, the ones that are regression-
 * shaped rather than eyeball-shaped. The simulator is pure and seeded, so
 * every assertion here is exact -- no tolerance for "Monte Carlo is random",
 * beyond the one place where sampling error is the thing being measured.
 */
class PlayoffOddsSimulatorTest {

    private static PlayoffOddsSimulator.TeamState team(int rosterId, double wins, double pointsFor) {
        return new PlayoffOddsSimulator.TeamState(rosterId, wins, 0, pointsFor, 100, 20);
    }

    /** Every roster paired off in one week: 1v2, 3v4, ... */
    private static List<PlayoffOddsSimulator.Fixture> week(int week, int... rosterIds) {
        List<PlayoffOddsSimulator.Fixture> out = new ArrayList<>();
        for (int i = 0; i < rosterIds.length; i++) {
            out.add(new PlayoffOddsSimulator.Fixture(week, rosterIds[i], i / 2 + 1));
        }
        return out;
    }

    private static Map<Integer, PlayoffOddsSimulator.Odds> byRoster(List<PlayoffOddsSimulator.Odds> odds) {
        Map<Integer, PlayoffOddsSimulator.Odds> out = new HashMap<>();
        for (PlayoffOddsSimulator.Odds o : odds) out.put(o.rosterId(), o);
        return out;
    }

    /**
     * Acceptance criterion 2, and the cheapest possible check that the seeding
     * step is not double-counting: exactly {@code playoffTeams} rosters make it
     * in every single simulated season, so the percentages have to add up to
     * {@code playoffTeams * 100}.
     */
    @Test
    void oddsSumToTheNumberOfPlayoffSpots() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(
                team(1, 0, 0), team(2, 0, 0), team(3, 0, 0), team(4, 0, 0));
        List<PlayoffOddsSimulator.Fixture> fixtures = new ArrayList<>();
        fixtures.addAll(week(1, 1, 2, 3, 4));
        fixtures.addAll(week(2, 1, 3, 2, 4));

        var odds = PlayoffOddsSimulator.run(teams, fixtures, 2, false, 2000, 42L);

        double sum = odds.stream().mapToDouble(PlayoffOddsSimulator.Odds::madePct).sum();
        assertEquals(200.0, sum, 0.01);
        assertEquals(100.0, odds.stream().mapToDouble(PlayoffOddsSimulator.Odds::seedOnePct).sum(), 0.01);
    }

    /**
     * With no games left the question is answered, not simulated: the standings
     * ARE the result, and 0/100 are real answers rather than the "--" a missing
     * snapshot renders.
     */
    @Test
    void noGamesLeftMeansTheStandingsDecideIt() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(
                team(1, 10, 1400), team(2, 9, 1300), team(3, 2, 900), team(4, 1, 800));

        var odds = byRoster(PlayoffOddsSimulator.run(teams, List.of(), 2, false, 500, 7L));

        assertEquals(100.0, odds.get(1).madePct());
        assertEquals(100.0, odds.get(2).madePct());
        assertEquals(0.0, odds.get(3).madePct());
        assertEquals(0.0, odds.get(4).madePct());
        assertEquals(100.0, odds.get(1).seedOnePct());
    }

    /** Points for is the tiebreak, exactly as Sleeper's default standings order has it. */
    @Test
    void equalRecordsBreakOnPointsFor() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(
                team(1, 5, 1200), team(2, 5, 1100), team(3, 5, 1000), team(4, 5, 900));

        var odds = byRoster(PlayoffOddsSimulator.run(teams, List.of(), 2, false, 100, 1L));

        assertEquals(100.0, odds.get(1).madePct());
        assertEquals(100.0, odds.get(2).madePct());
        assertEquals(0.0, odds.get(3).madePct());
    }

    /** Same seed, same answer -- a page refresh must not wiggle anyone's odds. */
    @Test
    void sameSeedGivesTheSameAnswer() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(team(1, 0, 0), team(2, 0, 0),
                team(3, 0, 0), team(4, 0, 0));
        List<PlayoffOddsSimulator.Fixture> fixtures = week(1, 1, 2, 3, 4);

        assertEquals(PlayoffOddsSimulator.run(teams, fixtures, 2, false, 500, 99L),
                PlayoffOddsSimulator.run(teams, fixtures, 2, false, 500, 99L));
        assertNotEquals(PlayoffOddsSimulator.run(teams, fixtures, 2, false, 500, 99L),
                PlayoffOddsSimulator.run(teams, fixtures, 2, false, 500, 100L));
    }

    /**
     * {@code league_average_match} doubles the games played: a normal week hands
     * out one win per pair (n/2 across the league), a median week hands out
     * another for every team that beat the median (n/2 more). It is invisible in
     * the fixture list, which is exactly why it is easy to miss.
     */
    @Test
    void medianMatchDoublesTheGamesPlayed() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(team(1, 0, 0), team(2, 0, 0),
                team(3, 0, 0), team(4, 0, 0));
        List<PlayoffOddsSimulator.Fixture> fixtures = week(1, 1, 2, 3, 4);

        double plain = PlayoffOddsSimulator.run(teams, fixtures, 2, false, 1000, 5L)
                .stream().mapToDouble(PlayoffOddsSimulator.Odds::projWins).sum();
        double median = PlayoffOddsSimulator.run(teams, fixtures, 2, true, 1000, 5L)
                .stream().mapToDouble(PlayoffOddsSimulator.Odds::projWins).sum();

        // Tolerance is per-team rounding, not model noise: projWins is rounded
        // to two places per roster (it is stored as numeric(5,2)) before these
        // four are summed.
        assertEquals(2.0, plain, 0.02);
        assertEquals(4.0, median, 0.02);
    }

    /** The median game is a win, not points: doubling the points would inflate the tiebreak. */
    @Test
    void medianMatchAddsNoPoints() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(team(1, 0, 0), team(2, 0, 0));
        List<PlayoffOddsSimulator.Fixture> fixtures = week(1, 1, 2);

        double plain = PlayoffOddsSimulator.run(teams, fixtures, 1, false, 500, 3L)
                .stream().mapToDouble(PlayoffOddsSimulator.Odds::projPoints).sum();
        double median = PlayoffOddsSimulator.run(teams, fixtures, 1, true, 500, 3L)
                .stream().mapToDouble(PlayoffOddsSimulator.Odds::projPoints).sum();

        assertEquals(plain, median, 0.001);
    }

    /** A matchup group without two sides is a bye, not a game, and nobody banks a win for it. */
    @Test
    void anUnpairedRosterDoesNotPlay() {
        List<PlayoffOddsSimulator.TeamState> teams = List.of(team(1, 0, 0), team(2, 0, 0), team(3, 0, 0));
        List<PlayoffOddsSimulator.Fixture> fixtures = List.of(
                new PlayoffOddsSimulator.Fixture(1, 1, 1),
                new PlayoffOddsSimulator.Fixture(1, 2, 1),
                new PlayoffOddsSimulator.Fixture(1, 3, 2));

        var odds = byRoster(PlayoffOddsSimulator.run(teams, fixtures, 2, false, 200, 11L));

        assertEquals(0.0, odds.get(3).projWins());
        assertEquals(0.0, odds.get(3).projPoints());
        assertEquals(1.0, odds.get(1).projWins() + odds.get(2).projWins(), 0.02);
    }

    // --- strength estimation ---------------------------------------------

    /** n=0: a roster that has not played is the league average, not a zero. */
    @Test
    void arosterWithNoGamesIsLeagueAverage() {
        Map<Integer, List<Double>> weekly = new LinkedHashMap<>();
        weekly.put(1, List.of(120.0, 130.0, 110.0));
        weekly.put(2, List.of(90.0, 100.0, 80.0));
        weekly.put(3, List.of());

        var strengths = PlayoffOddsSimulator.strengths(weekly);

        double leagueMean = (120 + 130 + 110 + 90 + 100 + 80) / 6.0;
        assertEquals(leagueMean, strengths.get(3)[0], 0.001);
        assertTrue(strengths.get(3)[1] > 0, "an unplayed roster still needs a spread to simulate");
    }

    /**
     * Shrinkage, the whole point of K: three games of scoring leaves a team
     * pulled most of the way back toward the league, so week 2 does not get to
     * have loud opinions.
     */
    @Test
    void aHotStartIsShrunkTowardTheLeague() {
        Map<Integer, List<Double>> weekly = new LinkedHashMap<>();
        weekly.put(1, List.of(160.0, 160.0, 160.0));
        weekly.put(2, List.of(100.0, 100.0, 100.0));
        weekly.put(3, List.of(100.0, 100.0, 100.0));

        double mu = PlayoffOddsSimulator.strengths(weekly).get(1)[0];

        double leagueMean = (160 * 3 + 100 * 6) / 9.0;
        double expected = (3 / (3 + PlayoffOddsSimulator.K)) * 160.0
                + (1 - 3 / (3 + PlayoffOddsSimulator.K)) * leagueMean;
        assertEquals(expected, mu, 0.001);
        assertTrue(mu < 160.0 && mu > leagueMean, "shrunk toward the league, not all the way to it");
    }
}
