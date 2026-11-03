package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** claude/official-week-scores.md: week N = fpts(N) - fpts(N-1), fpts(0) = 0, and never half a week. */
class RosterFptsSnapshotDerivationTest {

    private static Map<Integer, BigDecimal> snap(String... byRoster) {
        Map<Integer, BigDecimal> m = new HashMap<>();
        for (int i = 0; i < byRoster.length; i++) if (byRoster[i] != null) m.put(i + 1, new BigDecimal(byRoster[i]));
        return m;
    }

    @Test
    void weekOneIsItsOwnTotalAndLaterWeeksAreDifferences() {
        var out = RosterFptsSnapshotRepository.officialWeekScores(
                Map.of(1, snap("100.50", "90"), 2, snap("230.00", "180.25")), Set.of(1, 2));
        assertEquals(new BigDecimal("100.50"), out.get(1).get(1));
        assertEquals(new BigDecimal("129.50"), out.get(2).get(1));
        assertEquals(new BigDecimal("90.25"), out.get(2).get(2));
    }

    @Test
    void aMissingPreviousLegLeavesTheWeekToTheFeed() {
        var out = RosterFptsSnapshotRepository.officialWeekScores(
                Map.of(2, snap("230", "180"), 4, snap("500", "400")), Set.of(1, 2));
        assertTrue(out.isEmpty());
    }

    @Test
    void aRosterMissingFromEitherLegLeavesTheWholeWeekToTheFeed() {
        var out = RosterFptsSnapshotRepository.officialWeekScores(
                Map.of(1, snap("100", null), 2, snap("200", "150"), 3, snap("300", "260")), Set.of(1, 2));
        assertFalse(out.containsKey(1));
        assertFalse(out.containsKey(2));
        assertEquals(new BigDecimal("110"), out.get(3).get(2));
    }
}
