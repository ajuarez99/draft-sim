package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Holder;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Kind;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.PlayerContribution;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Standing;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.TrendingCoverage;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 051 plan-review F12: {@code Superlative} gained a 15th component. Once the 14-argument overload
 * exists, a rebuilder that still compiled against it would silently drop the field, so each rebuilder is
 * driven here with a TRENDING_TONY superlative and must hand the coverage back.
 */
class SuperlativeRebuildersTest {

    private static final TrendingCoverage COVERAGE =
            new TrendingCoverage(OffsetDateTime.of(2026, 10, 28, 11, 0, 4, 0, ZoneOffset.UTC), 31, 58, 89);

    private static Superlative trendingTony() {
        Holder h = new Holder(3, 7L, "Team Three", null, "av");
        return new Superlative(Kind.TRENDING_TONY, true, null, false, 4.0, "PICKUPS", List.of(h), null, List.of(),
                null, List.of(), List.of(new Standing(1, h, 4.0, "4 of 9 judged pickups", true, null)), List.of(),
                List.of(new PlayerContribution(1, "p1", "Player One", "WR", h, 2.0, "week 3 · waiver")),
                COVERAGE);
    }

    @Test
    void withUsernamesKeepsTrendingCoverageAndFillsUsernames() {
        Superlative out = SeasonSuperlativesService.withUsernames(trendingTony(), Map.of(3, "threeUser"));

        assertEquals(COVERAGE, out.trendingCoverage());
        assertEquals("threeUser", out.holders().get(0).username());
        assertEquals("threeUser", out.standings().get(0).team().username());
        assertEquals("threeUser", out.playerContributions().get(0).team().username());
    }

    @Test
    void withStandingsKeepsTrendingCoverage() throws Exception {
        var m = SeasonSuperlativesService.class.getDeclaredMethod("withStandings", Superlative.class, List.class);
        m.setAccessible(true);

        Superlative out = (Superlative) m.invoke(null, trendingTony(), List.<Standing>of());

        assertEquals(COVERAGE, out.trendingCoverage());
        assertTrue(out.standings().isEmpty());
        assertEquals(1, out.playerContributions().size(), "contributions survive too");
    }

    @Test
    void theFourteenArgOverloadDefaultsCoverageToNull() {
        Superlative s = new Superlative(Kind.JABARI_SMITH_JR, true, null, false, null, "ADDS", List.of(), null,
                List.of(), null, List.of(), List.of(), List.of(), List.of());

        assertNull(s.trendingCoverage());
    }
}
