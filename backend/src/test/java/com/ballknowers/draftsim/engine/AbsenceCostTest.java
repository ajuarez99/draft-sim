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
 * in-memory -- no Postgres. Rewritten for spec 040 (2026-10-20): a counted
 * (roster, player, week) is rostered + has an absence row + the player
 * played ZERO games that week, and each counted week costs one
 * {@code pointsPerGame}. Every fixture here therefore leaves a missed week
 * OUT of the player's played weeks -- the old fixtures listed missed weeks as
 * played, which the per-week rule would (correctly) charge nothing for.
 */
class AbsenceCostTest {

    private static RosterMembership member(int rosterId, String playerId, Set<Integer> rostered, Set<Integer> started) {
        return new RosterMembership(rosterId, playerId, rostered, started);
    }

    private static Set<Integer> weeksThrough(int n) {
        return IntStream.rangeClosed(1, n).boxed().collect(Collectors.toSet());
    }

    @Test
    void aRegularWhoMissedFiveWeeksOutranksOneWhoMissedTwoAtTheSamePointsPerGame() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4, 5, 6, 7, 8), Set.of(1, 2, 3, 4, 5, 6, 7, 8)),
                member(2, "P2", Set.of(1, 2, 3, 4, 5, 6, 7, 8), Set.of(1, 2, 3, 4, 5, 6, 7, 8)));
        // Spec 040 FR-001 (re-derived): a counted week needs ZERO games played, so the weeks he
        // missed (P1: 2-6, P2: 2-3) are not in his played weeks. The old fixture listed all 8 as
        // played, which under the per-week rule would charge nothing.
        Map<String, Set<Integer>> playedWeeks = Map.of(
                "P1", Set.of(1, 7, 8), "P2", Set.of(1, 4, 5, 6, 7, 8));
        Map<String, Double> ppg = Map.of("P1", 20.0, "P2", 20.0);
        List<Absence> absences = List.of(
                new Absence("P1", 2), new Absence("P1", 3), new Absence("P1", 4),
                new Absence("P1", 5), new Absence("P1", 6),
                new Absence("P2", 2), new Absence("P2", 3));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.get(1).totalPointsLost() > result.get(2).totalPointsLost());
        assertEquals(5, result.get(1).players().get(0).weeksMissed());
        assertEquals(2, result.get(2).players().get(0).weeksMissed());
        assertEquals(100.0, result.get(1).totalPointsLost(), 1e-9); // 5 weeks x 20
        assertEquals(40.0, result.get(2).totalPointsLost(), 1e-9);  // 2 weeks x 20
    }

    /**
     * <b>The award's own namesake case (coordinator follow-up 2026-09-23).</b>
     * A star started weeks 1-5 of an 18-week season, then missed the rest to
     * injury. The denominator is only the weeks he actually played -- 5 -- so
     * 5 &gt;= 5/2 qualifies. Football-shaped (one absence, no game, per
     * week), so the 13 counted weeks cost exactly what 13 missed games did
     * before spec 040.
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
        assertEquals(13, pc.weeksMissed());
        assertEquals(13, pc.gamesMissed());
        assertEquals(13 * 20.0, pc.estimatedPointsLost(), 1e-9);
    }

    /**
     * Spec 040 FR-001 (supersedes the 2026-09-23 per-game rule this test used to pin): three missed
     * nights in a week he ALSO played the fourth cost NOTHING -- he showed up that week.
     */
    @Test
    void aWeekWithAnyGamePlayedCostsNothingEvenWithThreeMissedNights() {
        List<RosterMembership> memberships = List.of(member(1, "P1", Set.of(4, 5), Set.of(4, 5)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(4, 5));
        Map<String, Double> ppg = Map.of("P1", 10.0);
        List<Absence> absences = List.of(new Absence("P1", 5), new Absence("P1", 5), new Absence("P1", 5));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.isEmpty(), "week 5 had a played game, so it is not a counted week");
    }

    /** (a) A zero-play week with 4 absence rows costs exactly one pointsPerGame. */
    @Test
    void aZeroPlayWeekWithFourAbsenceRowsCostsExactlyOnePointsPerGame() {
        List<RosterMembership> memberships = List.of(member(1, "P1", Set.of(3, 4, 5), Set.of(3, 4, 5)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(3, 4)); // week 5: no games
        List<Absence> absences = List.of(new Absence("P1", 5), new Absence("P1", 5),
                new Absence("P1", 5), new Absence("P1", 5));

        PlayerCost pc = AbsenceCost.compute(memberships, playedWeeks, Map.of("P1", 25.0), absences)
                .get(1).players().get(0);

        assertEquals(1, pc.weeksMissed());
        assertEquals(4, pc.gamesMissed());
        assertEquals(25.0, pc.estimatedPointsLost(), 1e-9);
    }

    /** (c) A zero-play week with 1 absence row costs the same as one with 4. */
    @Test
    void oneAbsenceRowAndFourAbsenceRowsInAZeroPlayWeekCostTheSame() {
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(3, 4));
        RosterMembership m = member(1, "P1", Set.of(3, 4, 5), Set.of(3, 4, 5));
        double one = AbsenceCost.compute(List.of(m), playedWeeks, Map.of("P1", 25.0),
                List.of(new Absence("P1", 5))).get(1).totalPointsLost();
        double four = AbsenceCost.compute(List.of(m), playedWeeks, Map.of("P1", 25.0),
                List.of(new Absence("P1", 5), new Absence("P1", 5), new Absence("P1", 5), new Absence("P1", 5)))
                .get(1).totalPointsLost();
        assertEquals(one, four, 1e-9);
    }

    /**
     * (d) Preference ordering (AGENTS.md recurring bug class 1). Roster A lost 3 whole weeks of a
     * 20-ppg player (3 x 20 = 60). Roster B lost 1 whole week of a 40-ppg player (40) plus 3
     * partial weeks of the same player (0 -- he played in them). A must outrank B; if the partial
     * weeks were charged B would be 160 and the order would flip.
     */
    @Test
    void moreZeroPlayWeeksOfALowerScorerOutranksOneZeroPlayWeekPlusPartialWeeks() {
        List<RosterMembership> memberships = List.of(
                member(1, "A", weeksThrough(8), weeksThrough(8)),
                member(2, "B", weeksThrough(8), weeksThrough(8)));
        // A: weeks 6, 7, 8 zero-play. B: week 5 zero-play; weeks 6, 7, 8 partial (absence row AND a played game).
        Map<String, Set<Integer>> playedWeeks = Map.of(
                "A", Set.of(1, 2, 3, 4, 5), "B", Set.of(1, 2, 3, 4, 6, 7, 8));
        Map<String, Double> ppg = Map.of("A", 20.0, "B", 40.0);
        List<Absence> absences = List.of(
                new Absence("A", 6), new Absence("A", 7), new Absence("A", 8),
                new Absence("B", 5), new Absence("B", 6), new Absence("B", 7), new Absence("B", 8));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertEquals(60.0, result.get(1).totalPointsLost(), 1e-9);
        assertEquals(40.0, result.get(2).totalPointsLost(), 1e-9);
        assertTrue(result.get(1).totalPointsLost() > result.get(2).totalPointsLost());
        assertEquals(1, result.get(2).players().get(0).weeksMissed());
        assertEquals(1, result.get(2).players().get(0).gamesMissed(), "only the zero-play week's row is counted");
    }

    /** (e) Football-shaped input: one absence and no game per missed week gives the old rule's total. */
    @Test
    void footballShapedInputGivesTheSameTotalsTheOldRuleDid() {
        List<RosterMembership> memberships = List.of(member(1, "P1", weeksThrough(6), weeksThrough(6)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 5, 6));
        List<Absence> absences = List.of(new Absence("P1", 3), new Absence("P1", 4));

        PlayerCost pc = AbsenceCost.compute(memberships, playedWeeks, Map.of("P1", 14.5), absences)
                .get(1).players().get(0);

        // Old rule: gamesMissed (2) x ppg (14.5) = 29.0. New rule: weeksMissed (2) x ppg = 29.0.
        assertEquals(2, pc.weeksMissed());
        assertEquals(2, pc.gamesMissed());
        assertEquals(29.0, pc.estimatedPointsLost(), 1e-9);
    }

    /** (f) gamesMissed counts only absence rows inside counted weeks. */
    @Test
    void gamesMissedCountsOnlyAbsenceRowsInsideCountedWeeks() {
        List<RosterMembership> memberships = List.of(member(1, "P1", weeksThrough(6), weeksThrough(6)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4)); // 5, 6 zero-play; 3, 4 partial
        List<Absence> absences = List.of(
                new Absence("P1", 3), new Absence("P1", 4), new Absence("P1", 4), // partial weeks: ignored
                new Absence("P1", 5), new Absence("P1", 5),                       // counted, 2 rows
                new Absence("P1", 6));                                            // counted, 1 row

        PlayerCost pc = AbsenceCost.compute(memberships, playedWeeks, Map.of("P1", 10.0), absences)
                .get(1).players().get(0);

        assertEquals(2, pc.weeksMissed());
        assertEquals(3, pc.gamesMissed());
        assertEquals(20.0, pc.estimatedPointsLost(), 1e-9);
    }

    @Test
    void aStashCostsNothing() {
        // Started fewer than 2 weeks -- pure bench stash.
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4, 5, 6), Set.of(1)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 5, 6));
        Map<String, Double> ppg = Map.of("P1", 15.0);
        List<Absence> absences = List.of(new Absence("P1", 3), new Absence("P1", 4));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.isEmpty(), "fewer than 2 started weeks never qualifies as a regular contributor");
    }

    @Test
    void aStashStartedLessThanHalfHisRosteredAndPlayedWeeksAlsoCostsNothing() {
        // 2 started weeks, but rostered AND played for 9 -- well under half.
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), Set.of(1, 2)));
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4, 6, 7, 8, 9, 10)); // week 5 zero-play
        Map<String, Double> ppg = Map.of("P1", 15.0);
        List<Absence> absences = List.of(new Absence("P1", 5));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertTrue(result.isEmpty(), "2 of 9 rostered-and-played weeks is well under half");
    }

    @Test
    void aMissedGameWhileOnAnotherFantasyRosterDoesNotCountForThisOne() {
        // Roster 1 held him weeks 1-4; roster 2 held him weeks 5-8 (traded).
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4), Set.of(1, 2, 3, 4)),
                member(2, "P1", Set.of(5, 6, 7, 8), Set.of(5, 6, 7, 8)));
        // Spec 040: weeks 6 and 7 are zero-play (he missed them), so they are not in his played weeks.
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1, 2, 3, 4, 5, 8));
        Map<String, Double> ppg = Map.of("P1", 12.0);
        // Missed weeks 6 and 7, both while on roster 2.
        List<Absence> absences = List.of(new Absence("P1", 6), new Absence("P1", 7));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertNull(result.get(1), "roster 1 never held him during either missed game");
        assertEquals(2, result.get(2).players().get(0).weeksMissed());
    }

    @Test
    void estimatedPointsLostEqualsWeeksMissedTimesPointsPerGame() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(1, 2, 3, 4), Set.of(1, 2, 3, 4)));
        // Spec 040: weeks 2-4 are zero-play, so only week 1 is a played week. Started weeks stay 1-4
        // so he still qualifies as a regular (4 started >= 1/2 of the 1 rostered-and-played week).
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(1));
        Map<String, Double> ppg = Map.of("P1", 17.25);
        List<Absence> absences = List.of(new Absence("P1", 2), new Absence("P1", 3), new Absence("P1", 4));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        PlayerCost pc = result.get(1).players().get(0);
        assertEquals(3, pc.weeksMissed());
        assertEquals(pc.weeksMissed() * pc.pointsPerGame(), pc.estimatedPointsLost(), 1e-6);
    }

    /**
     * Coordinator follow-up 2026-09-23: an UNCLASSIFIED {@code player_absence}
     * row (V23) must never cost a roster anything. {@link AbsenceCost} has no
     * concept of {@code basis} at all -- the caller filters UNCLASSIFIED rows
     * out before ever building an {@link Absence}, so this pins the contract
     * from this class's own side: only the entries actually passed in count.
     */
    @Test
    void aWeekWithNoAbsenceEntryAtAllNeverCostsAnything() {
        List<RosterMembership> memberships = List.of(
                member(1, "P1", Set.of(2, 3, 4, 5), Set.of(2, 3, 4, 5)));
        // Spec 040: weeks 2 and 3 are zero-play (real misses); week 5 is the unclassified week and
        // stays "played" here.
        Map<String, Set<Integer>> playedWeeks = Map.of("P1", Set.of(4, 5));
        Map<String, Double> ppg = Map.of("P1", 10.0);
        List<Absence> absences = List.of(new Absence("P1", 2), new Absence("P1", 3));

        Map<Integer, RosterCost> result = AbsenceCost.compute(memberships, playedWeeks, ppg, absences);

        assertEquals(2, result.get(1).players().get(0).weeksMissed(),
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
