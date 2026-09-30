package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Holder;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;
import com.ballknowers.draftsim.engine.SuperlativeStandings.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 010 T006: the ranking core, no Postgres. */
class SuperlativeStandingsTest {

    private static Holder h(int id) {
        return new Holder(id, null, "Team " + id, null, null);
    }

    private static Entry e(int id, Double v) {
        return new Entry(h(id), v, null, v == null ? "no data" : null);
    }

    private static List<Integer> rosters(List<Standing> s) {
        return s.stream().map(r -> r.team().rosterId()).toList();
    }

    private static List<Integer> ranks(List<Standing> s) {
        return s.stream().map(Standing::rank).toList();
    }

    @Test
    void descendingOrdersHighToLow() {
        List<Standing> s = SuperlativeStandings.rank(List.of(e(1, 10.0), e(2, 30.0), e(3, 20.0)), false);
        assertEquals(List.of(2, 3, 1), rosters(s));
        assertEquals(List.of(1, 2, 3), ranks(s));
    }

    @Test
    void ascendingOrdersLowToHigh() {
        List<Standing> s = SuperlativeStandings.rank(List.of(e(1, 10.0), e(2, 30.0), e(3, 20.0)), true);
        assertEquals(List.of(1, 3, 2), rosters(s));
        assertEquals(List.of(1, 2, 3), ranks(s));
    }

    @Test
    void tiesShareRankAndNextSkips() {
        List<Standing> s = SuperlativeStandings.rank(List.of(e(3, 5.0), e(1, 9.0), e(2, 9.0)), false);
        assertEquals(List.of(1, 2, 3), rosters(s)); // tie broken by rosterId ascending
        assertEquals(List.of(1, 1, 3), ranks(s));
    }

    @Test
    void threeWayTieAtTopThenFourth() {
        List<Standing> s = SuperlativeStandings.rank(List.of(e(4, 1.0), e(1, 8.0), e(2, 8.0), e(3, 8.0)), false);
        assertEquals(List.of(1, 1, 1, 4), ranks(s));
    }

    @Test
    void ascendingTiesUseTheSameRule() {
        List<Standing> s = SuperlativeStandings.rank(List.of(e(1, 2.0), e(2, 2.0), e(3, 7.0)), true);
        assertEquals(List.of(1, 1, 3), ranks(s));
    }

    @Test
    void nullValueRowsSortLastUnrankedInBothDirections() {
        for (boolean asc : new boolean[]{true, false}) {
            List<Standing> s = SuperlativeStandings.rank(List.of(e(1, null), e(2, 4.0), e(3, 6.0), e(4, null)), asc);
            assertEquals(4, s.size());
            assertTrue(s.get(0).hasValue() && s.get(1).hasValue());
            Standing a = s.get(2);
            Standing b = s.get(3);
            assertFalse(a.hasValue());
            assertNull(a.rank());
            assertNull(a.value());
            assertEquals("no data", a.missingReason());
            assertFalse(b.hasValue());
            assertNull(b.rank());
            assertEquals(List.of(1, 4), List.of(a.team().rosterId(), b.team().rosterId()));
        }
    }

    @Test
    void rankedRowsCarryNoMissingReason() {
        Standing s = SuperlativeStandings.rank(List.of(e(1, 3.0)), false).get(0);
        assertTrue(s.hasValue());
        assertNull(s.missingReason());
    }

    @Test
    void nullValueWithoutReasonThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SuperlativeStandings.rank(List.of(new Entry(h(1), null, "n", null)), false));
    }

    @Test
    void zeroValueIsARealValueNotMissing() {
        List<Standing> s = SuperlativeStandings.rank(List.of(e(1, 0.0), e(2, 2.0)), false);
        assertEquals(List.of(2, 1), rosters(s));
        assertTrue(s.get(1).hasValue());
    }
}
