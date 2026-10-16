package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/037 T003: the {@code allowed} overload of {@link RivalryMatching#match}. Kept in its own class so
 * {@code RivalryMatchingTest} (spec 034) stays untouched -- that file passing unchanged is the proof the
 * overload did not move spec 034.
 */
class RivalryMatchingAllowedTest {

    private static RivalryMatching.PairScore table(Map<String, Double> byPair) {
        return (a, b) -> byPair.getOrDefault(Math.min(a, b) + "-" + Math.max(a, b), 0.0);
    }

    @Test
    void aDisallowedPairNeverAppearsEvenWhenItHasTheHighestScore() {
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 100.0);
        t.put("1-3", 1.0);
        t.put("2-4", 1.0);
        t.put("3-4", 1.0);
        Optional<RivalryMatching.Matching> m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L), table(t),
                (a, b) -> !(a == 1 && b == 2), 16);
        assertTrue(m.isPresent());
        assertTrue(m.get().pairs().stream().noneMatch(p -> p.a() == 1 && p.b() == 2));
        assertEquals(2.0, m.get().total(), 1e-9);
    }

    @Test
    void noFullMatchingMeansEmptyNotAnException() {
        // 4 managers; 1 may only pair with 2, and 2 may only pair with 1 -- but 1-2 is disallowed.
        Optional<RivalryMatching.Matching> m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L), (a, b) -> 1.0,
                (a, b) -> !(a == 1 && b == 2) && !(a == 1 && b == 3) && !(a == 1 && b == 4), 16);
        assertTrue(m.isEmpty());
    }

    @Test
    void whenTheOnlyPerfectMatchingNeedsADisallowedPairTheResultIsEmpty() {
        // Only 1-2 is allowed, so 3 and 4 can only be matched with each other -- which is not allowed.
        assertTrue(RivalryMatching.match(List.of(1L, 2L, 3L, 4L), (a, b) -> 1.0,
                (a, b) -> a == 1 && b == 2, 16).isEmpty());
    }

    @Test
    void anOddLeagueSitOutIsUnconstrainedButAnInfeasibleRemainderIsEmpty() {
        // 3 managers: someone sits out, the other two must pair. Disallow every pair that does not include 3:
        // 1-2 is out, so only 1-3 or 2-3 can form, and the sit-out (2 or 1) is free to be whoever is left.
        Optional<RivalryMatching.Matching> ok = RivalryMatching.match(List.of(1L, 2L, 3L), (a, b) -> 1.0,
                (a, b) -> !(a == 1 && b == 2), 16);
        assertTrue(ok.isPresent());
        assertNotNull(ok.get().unpaired());
        assertEquals(1, ok.get().pairs().size());
        // Disallow all three pairs: no one can pair, and sitting out only frees ONE manager.
        assertTrue(RivalryMatching.match(List.of(1L, 2L, 3L), (a, b) -> 1.0, (a, b) -> false, 16).isEmpty());
    }

    @Test
    void unpairedBestScoreConsidersOnlyAllowedPartners() {
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 5.0);
        t.put("3-4", 4.0);
        t.put("1-5", 0.5);
        t.put("2-5", 9.0);   // 5's best partner overall, but it is not allowed
        Optional<RivalryMatching.Matching> m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L, 5L), table(t),
                (a, b) -> !(a == 2 && b == 5), 16);
        assertTrue(m.isPresent());
        assertEquals(5L, m.get().unpaired());
        assertEquals(0.5, m.get().unpairedBestScore(), 1e-9);
    }

    @Test
    void aSitOutWithNoAllowedPartnerHasNoScoreToQuote() {
        // 5 may pair with nobody: it must sit out, and 0.0 would read as a measured "best available" score.
        Optional<RivalryMatching.Matching> m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L, 5L), (a, b) -> 1.0,
                (a, b) -> b != 5, 16);
        assertTrue(m.isPresent());
        assertEquals(5L, m.get().unpaired());
        assertTrue(Double.isNaN(m.get().unpairedBestScore()));
    }

    @Test
    void aDisallowedEdgeForcesTheDpToRearrangeOtherPairsToStayFeasible() {
        // Unconstrained: 1-2 (10) + 3-4 (0) + 5-6 (0) = 10. With 1-2 out, the DP must reshuffle all six.
        Map<String, Double> t = new HashMap<>();
        t.put("1-2", 10.0);
        t.put("1-3", 2.0);
        t.put("2-4", 2.0);
        t.put("5-6", 1.0);
        Optional<RivalryMatching.Matching> m = RivalryMatching.match(List.of(1L, 2L, 3L, 4L, 5L, 6L), table(t),
                (a, b) -> !(a == 1 && b == 2), 16);
        assertTrue(m.isPresent());
        assertEquals(5.0, m.get().total(), 1e-9);
        assertEquals(3, m.get().pairs().size());
    }

    @Test
    void anAlwaysTruePredicateReturnsExactlyWhatTheExistingMatchReturns() {
        List<List<Long>> idSets = new ArrayList<>();
        List<Map<String, Double>> tables = new ArrayList<>();
        // optimal-not-greedy
        idSets.add(List.of(1L, 2L, 3L, 4L));
        tables.add(Map.of("1-2", 10.0, "1-3", 6.0, "2-4", 6.0));
        // odd league sit-out
        idSets.add(List.of(1L, 2L, 3L, 4L, 5L));
        tables.add(Map.of("1-2", 5.0, "3-4", 4.0, "1-5", 0.5));
        // 11 managers, structured scores
        List<Long> eleven = new ArrayList<>();
        for (long i = 1; i <= 11; i++) eleven.add(i);
        Map<String, Double> big = new HashMap<>();
        for (long a = 1; a <= 11; a++) for (long b = a + 1; b <= 11; b++) big.put(a + "-" + b, (double) ((a * 7 + b * 13) % 10));
        idSets.add(eleven);
        tables.add(big);
        // shuffled ids, ties
        idSets.add(List.of(9L, 3L, 7L, 1L, 5L, 2L));
        tables.add(Map.of("1-2", 3.0, "3-5", 3.0, "7-9", 3.0));
        // all-zero even / odd
        idSets.add(List.of(40L, 10L, 30L, 20L));
        tables.add(Map.of());
        idSets.add(List.of(5L, 3L, 1L, 4L, 2L));
        tables.add(Map.of());
        // equal-weight optima
        idSets.add(List.of(1L, 2L, 3L, 4L));
        tables.add(Map.of("1-2", 2.0, "3-4", 3.0, "1-3", 3.0, "2-4", 2.0));
        // float noise inside EPS
        idSets.add(List.of(1L, 2L, 3L, 4L));
        tables.add(Map.of("1-2", 2.0, "3-4", 3.0, "1-3", 3.0 + 1e-12, "2-4", 2.0));
        for (int k = 0; k < idSets.size(); k++) {
            RivalryMatching.Matching old = RivalryMatching.match(idSets.get(k), table(tables.get(k)), 16);
            Optional<RivalryMatching.Matching> neu =
                    RivalryMatching.match(idSets.get(k), table(tables.get(k)), (a, b) -> true, 16);
            assertEquals(old, neu.orElseThrow(), "fixture " + k);
        }
    }
}
