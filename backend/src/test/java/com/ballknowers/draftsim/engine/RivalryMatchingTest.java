package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RivalryMatching.PairStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/034 T009, written BEFORE {@link RivalryMatching} (AGENTS.md bug class 1: a scoring change can be
 * well-formed and pointed backwards, and only a preference-ordering test catches that). Bonuses are the
 * hand-set starting values 1.5 / 2.5 (research R6); the tests pin ORDERINGS, not those numbers' meaning.
 */
class RivalryMatchingTest {

    private static final double PLAYOFF = 1.5;
    private static final double TITLE = 2.5;

    private static double score(PairStats s) {
        return RivalryMatching.score(s, PLAYOFF, TITLE);
    }

    /** meetings, a wins, b wins, ties, no playoff meetings. */
    private static PairStats rec(int a, int b, int t) {
        return new PairStats(a + b + t, a, b, t, 0, false);
    }

    // ---- preference ordering -------------------------------------------------------------

    @Test
    void aFiveFiveSeriesBeatsASingleOneNilMeeting() {
        assertTrue(score(rec(5, 5, 0)) > score(rec(1, 0, 0)));
    }

    @Test
    void anEvenFourFourBeatsALopsidedSevenOne() {
        assertTrue(score(rec(4, 4, 0)) > score(rec(7, 1, 0)));
    }

    @Test
    void aMeetingInAFinalBeatsAnOtherwiseEqualPair() {
        PairStats plain = new PairStats(4, 2, 2, 0, 0, false);
        PairStats withFinal = new PairStats(4, 2, 2, 0, 1, true);
        assertTrue(score(withFinal) > score(plain));
    }

    @Test
    void anEliminationMeetingBeatsNoneButLosesToAFinal() {
        PairStats none = new PairStats(2, 1, 1, 0, 0, false);
        PairStats elimination = new PairStats(2, 1, 1, 0, 1, false);
        PairStats finalGame = new PairStats(2, 1, 1, 0, 1, true);
        assertTrue(score(elimination) > score(none));
        assertTrue(score(finalGame) > score(elimination));
    }

    @Test
    void zeroMeetingsIsTheMinimum() {
        PairStats never = new PairStats(0, 0, 0, 0, 0, false);
        assertEquals(0.0, score(never), 1e-12);
        for (PairStats s : List.of(rec(1, 0, 0), rec(0, 1, 0), rec(1, 1, 0), rec(0, 0, 1), rec(7, 1, 0), rec(5, 5, 0))) {
            assertTrue(score(s) > score(never), s.toString());
        }
    }

    @Test
    void theStatedClosenessValuesHold() {
        // research R6 / plan-review F14: 1-0 0.5, 1-1 2, 2-0 0.667, 3-0 0.75, 5-5 10, 7-1 2.667
        assertEquals(0.5, score(rec(1, 0, 0)), 1e-9);
        assertEquals(2.0, score(rec(1, 1, 0)), 1e-9);
        assertEquals(2.0 / 3.0, score(rec(2, 0, 0)), 1e-9);
        assertEquals(0.75, score(rec(3, 0, 0)), 1e-9);
        assertEquals(10.0, score(rec(5, 5, 0)), 1e-9);
        assertEquals(8.0 / 3.0, score(rec(7, 1, 0)), 1e-9);
    }

    /** F14, pinned in the direction Allan was offered: a title-game rematch outranks a 2-2 series. */
    @Test
    void aSingleOneNilFinalOutranksATwoTwoSeries() {
        PairStats finalOnly = new PairStats(1, 1, 0, 0, 1, true);
        assertEquals(4.5, score(finalOnly), 1e-9);
        assertEquals(4.0, score(rec(2, 2, 0)), 1e-9);
        assertTrue(score(finalOnly) > score(rec(2, 2, 0)));
    }

    @Test
    void tiesCountTowardMeetingsButNotTowardEitherSide() {
        // 0-0-2: m = 2, |w-l| = 0 -> 2.0, same as 1-1
        assertEquals(score(rec(1, 1, 0)), score(new PairStats(2, 0, 0, 2, 0, false)), 1e-9);
    }

    // ---- the matching --------------------------------------------------------------------

    private static RivalryMatching.PairScore table(Map<String, Double> byPair) {
        return (a, b) -> byPair.getOrDefault(Math.min(a, b) + "-" + Math.max(a, b), 0.0);
    }

    @Test
    void theMatchingIsOptimalNotGreedy() {
        // Greedy takes A-B (10) then C-D (0) = 10. The optimum is A-C (6) + B-D (6) = 12.
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 10.0);
        t.put("1-3", 6.0);
        t.put("2-4", 6.0);
        RivalryMatching.Matching m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L), table(t), 16);
        assertEquals(12.0, m.total(), 1e-9);
        assertEquals(List.of(new RivalryMatching.Pair(1, 3, 6.0), new RivalryMatching.Pair(2, 4, 6.0)), m.pairs());
        assertNull(m.unpaired());
    }

    @Test
    void anOddLeagueLeavesExactlyOneManagerOutAndTheDpChoosesWho() {
        // 1-2 and 3-4 are worth something; 5 has nothing. The best total sits 5 out.
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 5.0);
        t.put("3-4", 4.0);
        t.put("1-5", 0.5);
        RivalryMatching.Matching m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L, 5L), table(t), 16);
        assertEquals(2, m.pairs().size());
        assertEquals(5L, m.unpaired());
        assertEquals(9.0, m.total(), 1e-9);
        assertEquals(0.5, m.unpairedBestScore(), 1e-9, "the sit-out's best available pairing is reported for an honest reason");
    }

    @Test
    void everyManagerAppearsExactlyOnce() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 11; i++) ids.add(i);
        Map<String, Double> t = new HashMap<>();
        for (long a = 1; a <= 11; a++) for (long b = a + 1; b <= 11; b++) t.put(a + "-" + b, (double) ((a * 7 + b * 13) % 10));
        RivalryMatching.Matching m = RivalryMatching.match(ids, table(t), 16);
        List<Long> seen = new ArrayList<>();
        for (RivalryMatching.Pair p : m.pairs()) { seen.add(p.a()); seen.add(p.b()); }
        seen.add(m.unpaired());
        assertEquals(11, seen.stream().distinct().count());
        assertEquals(5, m.pairs().size());
    }

    @Test
    void isDeterministicRunToRun() {
        List<Long> ids = List.of(9L, 3L, 7L, 1L, 5L, 2L);
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 3.0);
        t.put("3-5", 3.0);
        t.put("7-9", 3.0);
        assertEquals(RivalryMatching.match(ids, table(t), 16), RivalryMatching.match(ids, table(t), 16));
    }

    /** F12: the tie-break on an all-zero league is pinned: ascending ids pair off in order. */
    @Test
    void anAllZeroEvenLeaguePairsAscendingIdsInOrder() {
        RivalryMatching.Matching m = RivalryMatching.match(List.of(40L, 10L, 30L, 20L), (a, b) -> 0.0, 16);
        assertEquals(List.of(new RivalryMatching.Pair(10, 20, 0.0), new RivalryMatching.Pair(30, 40, 0.0)), m.pairs());
    }

    /** F12: with an odd count and every score equal, "i sits out" is tried first, so the LOWEST id sits out. */
    @Test
    void anAllZeroOddLeagueSitsOutTheLowestId() {
        RivalryMatching.Matching m = RivalryMatching.match(List.of(5L, 3L, 1L, 4L, 2L), (a, b) -> 0.0, 16);
        assertEquals(1L, m.unpaired());
        assertEquals(List.of(new RivalryMatching.Pair(2, 3, 0.0), new RivalryMatching.Pair(4, 5, 0.0)), m.pairs());
    }

    /** F12: two equal-weight optima; the first-found (lowest free index pairs with the lowest partner) wins. */
    @Test
    void equalWeightOptimaResolveToTheFirstFound() {
        // 1-2 + 3-4 = 5 and 1-3 + 2-4 = 5. 1 tries partner 2 first, so 1-2 / 3-4 wins.
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 2.0);
        t.put("3-4", 3.0);
        t.put("1-3", 3.0);
        t.put("2-4", 2.0);
        RivalryMatching.Matching m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L), table(t), 16);
        assertEquals(List.of(new RivalryMatching.Pair(1, 2, 2.0), new RivalryMatching.Pair(3, 4, 3.0)), m.pairs());
    }

    @Test
    void floatNoiseBelowOneEmNineCannotFlipATie() {
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 2.0);
        t.put("3-4", 3.0);
        t.put("1-3", 3.0 + 1e-12);   // a hair better, but inside the 1e-9 tolerance: still a tie
        t.put("2-4", 2.0);
        RivalryMatching.Matching m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L), table(t), 16);
        assertEquals(1L, m.pairs().get(0).a());
        assertEquals(2L, m.pairs().get(0).b());
    }

    @Test
    void refusesMoreManagersThanTheCapAndFewerThanTwo() {
        List<Long> seventeen = new ArrayList<>();
        for (long i = 1; i <= 17; i++) seventeen.add(i);
        assertThrows(IllegalArgumentException.class, () -> RivalryMatching.match(seventeen, (a, b) -> 0.0, 16));
        assertThrows(IllegalArgumentException.class, () -> RivalryMatching.match(List.of(1L), (a, b) -> 0.0, 16));
        assertDoesNotThrow(() -> RivalryMatching.match(seventeen, (a, b) -> 0.0, 17));
    }
}
