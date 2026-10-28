package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.TrendingPickups.Judged;
import com.ballknowers.draftsim.engine.TrendingPickups.Verdict;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;
import com.ballknowers.draftsim.store.SportTrendingRepository.TrendingSnapshotHeader;
import com.ballknowers.draftsim.store.SportTrendingRepository.TrendingSnapshotRank;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Trending Tony's judging rule (specs/051-trending-tony-award T008), in memory: no Spring, no
 * Postgres. The after-first choice (F3), the empty-snapshot skip (F13), the shared pickup predicate (F1)
 * and a preference-ordering case (AGENTS.md bug class 1) each get their own test.
 */
class TrendingPickupsTest {

    private static final Instant P = Instant.parse("2026-10-28T15:00:00Z");

    private static TrendingSnapshotHeader header(long id, Instant at, int entryCount) {
        return new TrendingSnapshotHeader(id, at.atOffset(ZoneOffset.UTC), 24, entryCount);
    }

    private static CompletedAdd add(String type, int week, Instant at, String playerId, int rosterId) {
        return new CompletedAdd(week, at, type, playerId, rosterId, null);
    }

    private static CompletedAdd waiver(String playerId, int rosterId) {
        return add("WAIVER", 3, P, playerId, rosterId);
    }

    /** Triples of (snapshot id, player id, rank). */
    private static Map<Long, Map<String, TrendingSnapshotRank>> ranks(Object... triples) {
        Map<Long, Map<String, TrendingSnapshotRank>> out = new HashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            long snap = (Long) triples[i];
            String player = (String) triples[i + 1];
            int rank = (Integer) triples[i + 2];
            out.computeIfAbsent(snap, k -> new HashMap<>())
                    .put(player, new TrendingSnapshotRank(snap, player, rank, 1000 - rank));
        }
        return out;
    }

    private static List<Judged> judge(List<CompletedAdd> adds, List<TrendingSnapshotHeader> headers,
                                      Map<Long, Map<String, TrendingSnapshotRank>> ranks) {
        return TrendingPickups.judge(adds, headers, ranks, 18, id -> true);
    }

    @Test
    void rank15IsTrendingAndRank16IsNot() {
        var h = List.of(header(1, P.plusSeconds(300), 25));
        // The repository already filters to rank <= TOP_N, so a rank-16 row reaching the judge must still not count.
        var r = ranks(1L, "a", 15, 1L, "b", 16);

        List<Judged> out = judge(List.of(waiver("a", 1), waiver("b", 1)), h, r);

        assertEquals(Verdict.TRENDING, out.get(0).verdict());
        assertEquals(15, out.get(0).trendingRank());
        assertEquals(Verdict.NOT_TRENDING, out.get(1).verdict());
        assertNull(out.get(1).trendingRank());
    }

    @Test
    void anAfterSnapshotWinsOverAnEarlierBeforeSnapshot() {
        // 23h before (player trending there) and 5 minutes after (player not trending): after-first says NOT_TRENDING.
        var h = List.of(header(1, P.minus(Duration.ofHours(23)), 25), header(2, P.plusSeconds(300), 25));
        var r = ranks(1L, "a", 1);

        Judged j = judge(List.of(waiver("a", 1)), h, r).get(0);

        assertEquals(Verdict.NOT_TRENDING, j.verdict());
        assertEquals(P.plusSeconds(300), j.snapshotAt().toInstant(), "judged by the after snapshot");
    }

    @Test
    void theEarliestAfterSnapshotJudgesNotALaterOne() {
        var h = List.of(header(3, P.plus(Duration.ofHours(20)), 25), header(2, P.plus(Duration.ofHours(1)), 25));
        var r = ranks(2L, "a", 4);

        Judged j = judge(List.of(waiver("a", 1)), h, r).get(0);

        assertEquals(Verdict.TRENDING, j.verdict());
        assertEquals(4, j.trendingRank());
    }

    @Test
    void aBeforeSnapshotJudgesWithin24hAndNotBeyond() {
        var near = List.of(header(1, P.minus(Duration.ofHours(24)).plusSeconds(60), 25)); // 23h59m before
        var far = List.of(header(1, P.minus(Duration.ofHours(24)).minusSeconds(60), 25)); // 24h01m before
        var r = ranks(1L, "a", 2);

        assertEquals(Verdict.TRENDING, judge(List.of(waiver("a", 1)), near, r).get(0).verdict());
        assertEquals(Verdict.NOT_JUDGED, judge(List.of(waiver("a", 1)), far, r).get(0).verdict());
    }

    @Test
    void whenSeveralBeforeSnapshotsExistTheLatestJudges() {
        var h = List.of(header(1, P.minus(Duration.ofHours(20)), 25), header(2, P.minus(Duration.ofHours(2)), 25));
        var r = ranks(1L, "a", 1);

        assertEquals(Verdict.NOT_TRENDING, judge(List.of(waiver("a", 1)), h, r).get(0).verdict());
    }

    @Test
    void anAfterSnapshotBeyond24hJudgesNothing() {
        var h = List.of(header(1, P.plus(Duration.ofHours(24)).plusSeconds(60), 25)); // 24h01m after
        var r = ranks(1L, "a", 1);

        Judged j = judge(List.of(waiver("a", 1)), h, r).get(0);

        assertEquals(Verdict.NOT_JUDGED, j.verdict());
        assertNull(j.snapshotAt());
    }

    @Test
    void anEmptySnapshotIsSkippedForTheNextUsableOneAndNothingUsableIsNotJudged() {
        var withFallback = List.of(header(1, P.plusSeconds(60), 0), header(2, P.plusSeconds(3600), 25));
        var r = ranks(2L, "a", 3);
        var onlyEmpty = List.of(header(1, P.plusSeconds(60), 0));

        assertEquals(Verdict.TRENDING, judge(List.of(waiver("a", 1)), withFallback, r).get(0).verdict());
        assertEquals(Verdict.NOT_JUDGED, judge(List.of(waiver("a", 1)), onlyEmpty, r).get(0).verdict());
    }

    @Test
    void aPickupWithNoTimestampIsNotJudged() {
        var h = List.of(header(1, P, 25));

        Judged j = judge(List.of(add("WAIVER", 3, null, "a", 1)), h, ranks(1L, "a", 1)).get(0);

        assertEquals(Verdict.NOT_JUDGED, j.verdict());
    }

    @Test
    void tradeAndCommissionerAddsOfATrendingPlayerAreInNoBucket() {
        var h = List.of(header(1, P, 25));
        var r = ranks(1L, "a", 1);
        List<CompletedAdd> adds = List.of(add("TRADE", 3, P, "a", 1), add("COMMISSIONER", 3, P, "a", 2),
                add("WAIVER", 3, P, "a", 3));

        List<Judged> out = judge(adds, h, r);

        assertEquals(1, out.size(), "only the waiver counts; trade and commissioner moves are not pickups");
        assertEquals(3, out.get(0).add().rosterId());
    }

    @Test
    void anIneligiblePlayerIsExcluded() {
        var h = List.of(header(1, P, 25));

        List<Judged> out = TrendingPickups.judge(List.of(waiver("DEF1", 1), waiver("a", 1)), h,
                ranks(1L, "DEF1", 1, 1L, "a", 2), 18, id -> !id.equals("DEF1"));

        assertEquals(1, out.size());
        assertEquals("a", out.get(0).add().playerId());
    }

    @Test
    void addsAfterThroughWeekAndWeekZeroAreExcluded() {
        var h = List.of(header(1, P, 25));
        List<CompletedAdd> adds = List.of(add("WAIVER", 5, P, "a", 1), add("WAIVER", 4, P, "b", 1),
                add("WAIVER", 0, P, "c", 1));

        List<Judged> out = TrendingPickups.judge(adds, h, ranks(1L, "a", 1, 1L, "b", 2, 1L, "c", 3), 4, id -> true);

        assertEquals(List.of("b"), out.stream().map(j -> j.add().playerId()).toList());
    }

    @Test
    void theBucketsSumToTheCountedPickups() {
        // SC-002: TRENDING + NOT_TRENDING + NOT_JUDGED == every counted pickup, whatever the verdicts are.
        var h = List.of(header(1, P, 25));
        var r = ranks(1L, "a", 1);
        List<CompletedAdd> adds = new ArrayList<>(List.of(
                waiver("a", 1), waiver("b", 2), add("WAIVER", 3, null, "c", 3),
                add("FREE_AGENT", 3, P.plus(Duration.ofDays(5)), "d", 4),
                add("TRADE", 3, P, "a", 5), add("WAIVER", 0, P, "a", 6)));

        List<Judged> out = judge(adds, h, r);

        long t = out.stream().filter(j -> j.verdict() == Verdict.TRENDING).count();
        long n = out.stream().filter(j -> j.verdict() == Verdict.NOT_TRENDING).count();
        long u = out.stream().filter(j -> j.verdict() == Verdict.NOT_JUDGED).count();
        assertEquals(4, t + n + u, "four counted pickups; the trade and the week-0 add are in no bucket");
        assertEquals(1, t);
        assertEquals(1, n);
        assertEquals(2, u);
    }

    @Test
    void moreTrendingPickupsOutranksMoreTotalPickups() {
        // Preference ordering (bug class 1): roster 1 has 3 trending of 3; roster 2 has 2 trending of 8.
        var h = List.of(header(1, P, 25));
        Map<Long, Map<String, TrendingSnapshotRank>> r = new HashMap<>();
        List<CompletedAdd> adds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            adds.add(waiver("r1p" + i, 1));
            r.computeIfAbsent(1L, k -> new HashMap<>()).putAll(ranks(1L, "r1p" + i, i + 1).get(1L));
        }
        for (int i = 0; i < 8; i++) adds.add(waiver("r2p" + i, 2));
        r.get(1L).putAll(ranks(1L, "r2p0", 4, 1L, "r2p1", 5).get(1L));

        var tally = TrendingPickups.tally(judge(adds, h, r));

        assertEquals(3, tally.get(1).trending());
        assertEquals(2, tally.get(2).trending());
        assertEquals(8, tally.get(2).judged());
        assertTrue(tally.get(1).trending() > tally.get(2).trending());
    }
}
