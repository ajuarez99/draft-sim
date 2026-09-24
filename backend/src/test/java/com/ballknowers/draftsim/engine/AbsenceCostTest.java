package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.AbsenceCost.Absence;
import com.ballknowers.draftsim.engine.AbsenceCost.PlayerCost;
import com.ballknowers.draftsim.engine.AbsenceCost.RosterCost;
import com.ballknowers.draftsim.engine.AbsenceCost.RosterMembership;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOEL_EMBIID rule tests (specs/008-season-superlatives T046), pure and
 * in-memory -- no Postgres. Each case is one item from research R10 (amended
 * 2026-09-23) and the task list.
 */
class AbsenceCostTest {

    private static RosterMembership member(int rosterId, String playerId, Set<Integer> rostered, Set<Integer> started) {
        return new RosterMembership(rosterId, playerId, rostered, started);
    }

    private static Set<Integer> weeksThrough(int n) {
        return IntStream.rangeClosed(1, n).boxed().collect(Collectors.toSet());
    }

    @Test
    void aRegularWhoMissedFiveGamesOutranksOneWhoMissedTwoAtTheSamePointsPerGame() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4, 5, 6, 7, 8), Set.of(1, 2, 3, 4, 5, 6, 7, 8)),
                member(2, "P2", Set.of(1, 2, 3, 4, 5, 6, 7, 8), Set.of(1, 2, 3, 4, 5, 6, 7, 8)));
        Map<String, Set<Integer>> playedWeeks = Map.of(
                "P1", Set.of(1, 2, 3, 4, 5, 6, 7, 8), "P2", Set.of(1, 2, 3, 4, 5, 6, 7, 8));
        Map<String, Double> ppg = Map.of("P1", 20.0, "P2", 20.0);
        List<Absence> absences = List.of(
                new Absence("P1", 2), new Absence("P1", 3), new Absence("P1", 4),
                new Absence("P1", 5), new Absence("P1", 6),
                new Absence("P2", 2), new Absence("P2", 3));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.get(1).totalPointsLost() > result.get(2).totalPointsLost());
        assertEquals(5, result.get(1).players().get(0).gamesMissed());
        assertEquals(2, result.get(2).players().get(0).gamesMissed());
    }

    /**
     * <b>The award's own namesake case (coordinator follow-up 2026-09-23,
     * bug found by live parent review).</b> A star started weeks 1-5 of an
     * 18-week season, then missed the rest to injury (13 straight missed
     * games). The old denominator (every rostered week, 18) made 5 &lt; 9
     * fail outright, dropping the exact case the award exists for. Fixed: the
     * denominator is only the weeks he actually played -- 5 -- so 5 &gt;= 5/2
     * qualifies, and he costs all 13 missed games.
     */
    @Test
    void aStarWhoPlayedFiveThenMissedTheRestOfAnEighteenWeekSeasonQualifies() {
        Set<Integer> rostered = weeksThrough(18);
        Set<Integer> started = Set.of(1, 2, 3, 4, 5);
        List<RosterMembership> memberships = List.of(member(1, "P1", rostered, started));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4, 5));
        Map<String, Double> ppg = Map.of("P1", 20.0);
        List<Absence> absences = new ArrayList<>();
        for (int wk = 6; wk <= 18; wk++) absences.add(new Absence("P1", wk));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertNotNull(result.get(1), "the award's own namesake case must qualify");
        PlayerCost pc = result.get(1).players().get(0);
        assertEquals(13, pc.gamesMissed());
        assertEquals(13 * 20.0, pc.estimatedPointsLost(), 1e-9);
    }

    /**
     * Allan's rule: three missed nights in a week he also played the fourth
     * cost THREE games. This is the amended per-game rule, not the rejected
     * per-week alternative.
     */
    @Test
    void threeMissedNightsInAWeekHePlayedTheFourthCostThreeGames() {
        // Two started weeks (4 and 5), satisfying the >=2-started-weeks and
        // half-of-rostered-AND-played-weeks regular-contributor gate on its
        // own; the three missed games all fall in week 5, the same week he
        // started (and played the fourth game of).
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(4, 5), Set.of(4, 5)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(4, 5));
        Map<String, Double> ppg = Map.of("P1", 10.0);
        // Four games in week 5; three missed, one played (the played one isn't in `absences`).
        List<Absence> absences = List.of(
                new Absence("P1", 5), new Absence("P1", 5), new Absence("P1", 5));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        PlayerCost pc = result.get(1).players().get(0);
        assertEquals(3, pc.gamesMissed(), "three missed games, even though he played a fourth in the same week");
        assertEquals(1, pc.weeksAffected(), "all three fell in the same fantasy week");
        assertEquals(30.0, pc.estimatedPointsLost(), 1e-9);
    }

    @Test
    void aStashCostsNothing() {
        // Started fewer than 2 weeks -- pure bench stash.
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4, 5, 6), Set.of(1)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4, 5, 6));
        Map<String, Double> ppg = Map.of("P1", 15.0);
        List<Absence> absences = List.of(new Absence("P1", 3), new Absence("P1", 4));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.isEmpty(), "fewer than 2 started weeks never qualifies as a regular contributor");
    }

    @Test
    void aStashStartedLessThanHalfHisRosteredAndPlayedWeeksAlsoCostsNothing() {
        // 2 started weeks, but rostered AND played for 10 -- well under half.
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), Set.of(1, 2)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        Map<String, Double> ppg = Map.of("P1", 15.0);
        List<Absence> absences = List.of(new Absence("P1", 5));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.isEmpty(), "2 of 10 rostered-and-played weeks is well under half");
    }

    @Test
    void aMissedGameWhileOnAnotherFantasyRosterDoesNotCountForThisOne() {
        // Roster 1 held him weeks 1-4; roster 2 held him weeks 5-8 (traded).
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4), Set.of(1, 2, 3, 4)),
                member(2, "P1", Set.of(5, 6, 7, 8), Set.of(5, 6, 7, 8)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4, 5, 8));
        Map<String, Double> ppg = Map.of("P1", 12.0);
        // Missed weeks 6 and 7, both while on roster 2.
        List<Absence> absences = List.of(new Absence("P1", 6), new Absence("P1", 7));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertNull(result.get(1), "roster 1 never held him during either missed game");
        assertEquals(2, result.get(2).players().get(0).gamesMissed());
    }

    @Test
    void estimatedPointsLostEqualsGamesMissedTimesPointsPerGame() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4), Set.of(1, 2, 3, 4)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4));
        Map<String, Double> ppg = Map.of("P1", 17.25);
        List<Absence> absences = List.of(new Absence("P1", 2), new Absence("P1", 3), new Absence("P1", 4));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        PlayerCost pc = result.get(1).players().get(0);
        assertEquals(pc.gamesMissed() * pc.pointsPerGame(), pc.estimatedPointsLost(), 1e-6);
    }

    /**
     * Coordinator follow-up 2026-09-23: an UNCLASSIFIED {@code player_absence}
     * row (V23) must never cost a roster anything. {@link AbsenceCost} has no
     * concept of {@code basis} at all -- the caller (
     * {@code SeasonSuperlativesService.absenceSuperlative}) filters
     * UNCLASSIFIED rows out before ever building an {@link Absence}, so this
     * pins the contract from this class's own side: an unclassified week that
     * never becomes an {@link Absence} entry costs nothing, only the entries
     * that are actually passed in do.
     */
    @Test
    void aWeekWithNoAbsenceEntryAtAllNeverCostsAnything() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(2, 3, 4, 5), Set.of(2, 3, 4, 5)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(2, 3, 4, 5));
        Map<String, Double> ppg = Map.of("P1", 10.0);
        // Weeks 2 and 3 are real missed games; week 5 would have been an
        // UNCLASSIFIED football `None` week and is simply never passed in.
        List<Absence> absences = List.of(new Absence("P1", 2), new Absence("P1", 3));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertEquals(2, result.get(1).players().get(0).gamesMissed(),
                "only the two real absences count; the unclassified week contributes nothing");
    }

    @Test
    void nobodyMissedAnythingIsEmpty() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4), Set.of(1, 2, 3, 4)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, Map.of("P1", 20.0), List.of());

        assertTrue(result.isEmpty(), "no absences at all -- the wiring layer supplies \"nobody's been bitten yet\"");
    }

    @Test
    void aPlayerWithNoGamesPlayedAtAllNeverQualifiesRegardlessOfStarts() {
        // Started every week but never actually played a game (e.g. only DNP entries).
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4), Set.of(1, 2, 3, 4)));
        Map<String, Set<Integer>> playedWeeks = Map.of(); // P1 not in the played-weeks map at all
        Map<String, Double> ppg = Map.of("P1", 20.0);
        List<Absence> absences = List.of(new Absence("P1", 2));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.isEmpty());
    }
}
