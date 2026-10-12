package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.LeagueAnalysisService.RosterProjection;
import com.ballknowers.draftsim.engine.SeasonStartService.Favorite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** specs/030-post-draft-season-view T033: the favorite math, pure (wire-final section 4). */
class SeasonStartFavoriteTest {

    private static final double MIN_GAP = 3.0;

    private static RosterProjection roster(int id, double total) {
        return new RosterProjection(id, (long) id, "u" + id, null, 0, false, total, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), 0, 0);
    }

    private static List<RosterProjection> rosters(double... totals) {
        java.util.ArrayList<RosterProjection> out = new java.util.ArrayList<>();
        for (int i = 0; i < totals.length; i++) out.add(roster(i + 1, totals[i]));
        return out;
    }

    @Test
    void aClearLeaderHasTheMarginOverTheNextTeamAndNoTie() {
        Favorite f = SeasonStartService.favorite(rosters(200.0, 150.0, 100.0), MIN_GAP, 1);

        assertFalse(f.tie());
        assertEquals(List.of(1), f.leaders().stream().map(SeasonStartService.Leader::rosterId).toList());
        assertEquals(200.0, f.leaderTotal(), 1e-9);
        assertEquals(150.0, f.nextTotal(), 1e-9);
        assertEquals(50.0, f.marginPoints(), 1e-9);
        // One denominator: the LOWER total. 50 / 150 = 33.3%.
        assertEquals(33.3, f.marginPct(), 1e-9);
        assertEquals(MIN_GAP, f.minGapPct(), 1e-9);
    }

    @Test
    void twoTeamsHalfAPercentApartAreATieAndBothAreLeaders() {
        Favorite f = SeasonStartService.favorite(rosters(200.0, 199.0, 150.0), MIN_GAP, 1);

        assertTrue(f.tie());
        assertEquals(List.of(1, 2), f.leaders().stream().map(SeasonStartService.Leader::rosterId).toList());
        assertEquals(150.0, f.nextTotal(), 1e-9, "the margin runs from the leader group to the first non-leader");
        assertEquals(50.0, f.marginPoints(), 1e-9);
    }

    @Test
    void anExactTieAtRankOneIsATie() {
        Favorite f = SeasonStartService.favorite(rosters(200.0, 200.0, 150.0), MIN_GAP, 1);

        assertTrue(f.tie());
        assertEquals(2, f.leaders().size());
    }

    @Test
    void aGapExactlyAtTheThresholdIsNotATie() {
        // 103 vs 100: gap over the lower total is exactly 3.0%, and the cut is strict (< 3.0).
        Favorite f = SeasonStartService.favorite(rosters(103.0, 100.0), MIN_GAP, 1);

        assertFalse(f.tie());
        assertEquals(3.0, f.marginPct(), 1e-9);
    }

    @Test
    void footballRestOfSeasonTotalsAreDividedToPerWeek() {
        Favorite f = SeasonStartService.favorite(rosters(1300.0, 1200.0), MIN_GAP, 13);

        assertEquals(7.7, f.marginPoints(), 1e-9, "100 points over 13 weeks");
        assertEquals(8.3, f.marginPct(), 1e-9, "a percentage does not depend on the window");
        assertEquals(1300.0, f.leaderTotal(), 1e-9, "totals stay in the unit the payload reports");
    }

    @Test
    void everyRosterWithinTheGapIsALeaderAndThereIsNoNext() {
        Favorite f = SeasonStartService.favorite(rosters(100.0, 99.0, 98.5), MIN_GAP, 1);

        assertTrue(f.tie());
        assertEquals(3, f.leaders().size());
        assertNull(f.nextTotal());
        assertNull(f.marginPoints());
        assertNull(f.marginPct());
    }

    @Test
    void aNonPositiveLowerTotalIsAnInfiniteGapNotAnArithmeticError() {
        Favorite f = SeasonStartService.favorite(rosters(5.0, 0.0), MIN_GAP, 1);

        assertFalse(f.tie());
        assertEquals(5.0, f.marginPoints(), 1e-9);
        assertNull(f.marginPct(), "an infinite percentage is not a number to print");
    }

    @Test
    void aSingleRosterOrNoRostersHasNoFavorite() {
        assertNull(SeasonStartService.favorite(rosters(100.0), MIN_GAP, 1));
        assertNull(SeasonStartService.favorite(List.of(), MIN_GAP, 1));
    }

    @Test
    void anUnsortedListIsRankedByTotalFirst() {
        Favorite f = SeasonStartService.favorite(rosters(150.0, 200.0), MIN_GAP, 1);

        assertEquals(List.of(2), f.leaders().stream().map(SeasonStartService.Leader::rosterId).toList());
    }
}
