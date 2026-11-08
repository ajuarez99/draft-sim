package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.store.WeekScoreResolver.Resolution;
import com.ballknowers.draftsim.store.WeekScoreResolver.Resolved;
import com.ballknowers.draftsim.store.WeekScoreResolver.Source;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 057: the one rule, commissioner &gt; plausible official &gt; feed, as a pure function. The official-tier
 * cases moved here from {@code WeeklyPotServiceTest} (spec 055), where the rule used to live; they keep their
 * intent: official beats feed, and the plausibility gate rejects a late-keyed snapshot and a negative week.
 */
class WeekScoreResolverTest {

    private static final long LEAGUE = 77L;
    private static final Set<Integer> ROSTERS = Set.of(1, 2, 3, 4);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Map<Integer, BigDecimal> by(String... byRoster) {
        Map<Integer, BigDecimal> m = new HashMap<>();
        for (int i = 0; i < byRoster.length; i++) m.put(i + 1, bd(byRoster[i]));
        return m;
    }

    /** Roster r scores 100 + r in each week (roster 4 tops the feed), total 410. */
    private static Map<Integer, Map<Integer, BigDecimal>> feed(int... weeks) {
        Map<Integer, Map<Integer, BigDecimal>> out = new HashMap<>();
        for (int w : weeks) out.put(w, by("101", "102", "103", "104"));
        return out;
    }

    private static Map<Integer, Resolved> resolve(Map<Integer, Map<Integer, BigDecimal>> overrides,
                                                  Map<Integer, Map<Integer, BigDecimal>> snapshots,
                                                  Map<Integer, Map<Integer, BigDecimal>> feed) {
        return WeekScoreResolver.resolveLeague(LEAGUE, overrides, snapshots, ROSTERS, feed);
    }

    private static Resolution of(Map<Integer, Resolved> weeks) {
        return Resolution.of(Map.of(LEAGUE, weeks));
    }

    @Test
    void anOverrideBeatsTheFeed() {
        // the preference test (lessons class 1): the feed has roster 4 on top, the entered scores roster 1.
        Map<Integer, Resolved> r = resolve(Map.of(1, by("300", "100", "100", "100")), Map.of(), feed(1));
        assertEquals(Source.COMMISSIONER, r.get(1).source());
        Resolution res = of(r);
        assertEquals(bd("300"), res.points(LEAGUE, 1, 1, bd("101")));
        assertEquals(bd("100"), res.points(LEAGUE, 1, 4, bd("104")));
        assertEquals(Source.COMMISSIONER, res.source(LEAGUE, 1));
    }

    @Test
    void anOverrideBeatsAPlausibleOfficialWeek() {
        // official week 1 would be 110/105/100/100 (415, 1.2% off the feed: plausible), and does win without an override
        Map<Integer, Map<Integer, BigDecimal>> snapshots = Map.of(1, by("110", "105", "100", "100"));
        assertEquals(Source.OFFICIAL, resolve(Map.of(), snapshots, feed(1)).get(1).source());

        Map<Integer, Resolved> r = resolve(Map.of(1, by("250", "100", "100", "100")), snapshots, feed(1));
        assertEquals(Source.COMMISSIONER, r.get(1).source());
        assertEquals(bd("250"), of(r).points(LEAGUE, 1, 1, bd("101")));
    }

    @Test
    void aPlausibleOfficialWeekBeatsTheFeed() {
        // week 1 official: roster 1 wins (110 of 415). week 2 = fpts(2) - fpts(1): roster 2 wins (130). Both within drift.
        Map<Integer, Map<Integer, BigDecimal>> snapshots = Map.of(
                1, by("110", "105", "100", "100"),
                2, by("215", "235", "200", "202"));
        Map<Integer, Resolved> r = resolve(Map.of(), snapshots, feed(1, 2));
        assertEquals(Source.OFFICIAL, r.get(1).source());
        assertEquals(Source.OFFICIAL, r.get(2).source());
        Resolution res = of(r);
        assertEquals(bd("110"), res.points(LEAGUE, 1, 1, bd("101")));
        assertEquals(bd("130"), res.points(LEAGUE, 2, 2, bd("102")));
    }

    @Test
    void aWeekWithoutTheEarlierLegFallsBackToTheFeed() {
        // only fpts(2) exists (capture started after week 1): week 2 cannot be separated from week 1
        Map<Integer, Resolved> r = resolve(Map.of(), Map.of(2, by("500", "100", "100", "100")), feed(1, 2));
        assertEquals(Source.FEED, r.get(2).source());
        assertEquals(Source.FEED, r.get(1).source());
    }

    @Test
    void aSnapshotTakenALegLateDoublesOneWeekAndZeroesTheNextSoBothFallBackToTheFeed() {
        // the leg-1 row actually holds two weeks (~2x the feed's 410), the leg-2 row repeats it (0s)
        Map<Integer, Map<Integer, BigDecimal>> snapshots = Map.of(
                1, by("300", "200", "200", "200"),
                2, by("300", "200", "200", "200"));
        Map<Integer, Resolved> r = resolve(Map.of(), snapshots, feed(1, 2));
        assertEquals(Source.FEED, r.get(1).source());
        assertEquals(Source.FEED, r.get(2).source());
    }

    @Test
    void aNegativeOfficialWeekFallsBackToTheFeed() {
        Map<Integer, Map<Integer, BigDecimal>> snapshots = Map.of(
                1, by("110", "100", "100", "100"),
                2, by("100", "210", "210", "210"));
        Map<Integer, Resolved> r = resolve(Map.of(), snapshots, feed(1, 2));
        assertEquals(Source.OFFICIAL, r.get(1).source());
        assertEquals(Source.FEED, r.get(2).source());
    }

    @Test
    void aCommissionerWeekMissingARosterTheFeedHasIsFeedWholesaleNeverMixed() {
        Map<Integer, BigDecimal> partial = by("300", "100", "100");   // roster 4 absent
        Map<Integer, Resolved> r = resolve(Map.of(1, partial), Map.of(), feed(1));
        assertEquals(Source.FEED, r.get(1).source());
        Resolution res = of(r);
        assertEquals(bd("101"), res.points(LEAGUE, 1, 1, bd("101")), "not the entered 300: the feed, whole week");
    }

    @Test
    void withNothingToResolveEveryReadIsTheIdentity() {
        Map<Integer, Resolved> r = resolve(Map.of(), Map.of(), feed(1));
        assertEquals(Source.FEED, r.get(1).source());
        Resolution res = of(r);
        assertTrue(res.isEmpty());
        BigDecimal feedValue = bd("101");
        assertSame(feedValue, res.points(LEAGUE, 1, 1, feedValue));
        assertSame(feedValue, res.points(999L, 1, 1, feedValue), "an unknown league is the feed");
        assertEquals(Source.FEED, res.source(999L, 5));
        assertTrue(Resolution.EMPTY.isEmpty());
    }

    @Test
    void aWeekOutsideTheResolutionIsTheFeed() {
        Resolution res = of(resolve(Map.of(1, by("300", "100", "100", "100")), Map.of(), feed(1, 2)));
        assertEquals(Source.FEED, res.source(LEAGUE, 2));
        assertEquals(bd("102"), res.points(LEAGUE, 2, 2, bd("102")));
        assertEquals(Map.of(1, Source.COMMISSIONER, 2, Source.FEED), res.sources(LEAGUE));
        assertNull(WeekScoreResolver.plausibleOfficial(null, by("1", "2", "3", "4")));
    }
}
