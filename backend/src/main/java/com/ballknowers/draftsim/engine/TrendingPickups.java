package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;
import com.ballknowers.draftsim.store.SportTrendingRepository.TrendingSnapshotHeader;
import com.ballknowers.draftsim.store.SportTrendingRepository.TrendingSnapshotRank;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The Trending Tony's judging rule (specs/051-trending-tony-award, research R1/R4, data-model.md):
 * was a waiver or free-agent pickup on Sleeper's platform-wide trending-adds list, in the top
 * {@link #TOP_N}, at the time it was made. Pure and static -- no Spring, no Postgres -- so
 * {@code TrendingPickupsTest} can exercise every case in memory.
 *
 * <p>A pickup is judged by exactly one stored snapshot: the <b>earliest</b> one taken within the
 * snapshot's own lookback after the pickup (its window literally contains the pickup), else the
 * <b>latest</b> one taken within that lookback before it. No usable snapshot means
 * {@link Verdict#NOT_JUDGED}, which is deliberately not the same claim as {@link Verdict#NOT_TRENDING}
 * (a gap in history must never read as "was not trending").
 */
public final class TrendingPickups {

    private TrendingPickups() {}

    /**
     * How deep in Sleeper's list still counts as "trending". <b>Hand-set, arbitrary</b>: decided at
     * spec clarification, not fitted from anything. Sleeper's list is stored 25 deep, so a player
     * absent from the stored rows is below 15 for certain.
     */
    public static final int TOP_N = 15;

    /**
     * The fewest trending pickups a manager needs before the award names him. <b>Hand-set,
     * arbitrary</b>, the same shape of floor as {@link MostAddedPlayers#MIN_ADDS_TO_NAME}: one is a
     * coincidence, two is a habit. Below it the card says nobody has chased the list twice.
     */
    public static final int MIN_TO_NAME = 2;

    public enum Verdict { TRENDING, NOT_TRENDING, NOT_JUDGED }

    /**
     * One counted pickup and how it was judged. {@code trendingRank}, {@code addCount} are set only
     * for {@link Verdict#TRENDING}; {@code snapshotAt} is the judging snapshot's time for TRENDING
     * and NOT_TRENDING, null for NOT_JUDGED.
     */
    public record Judged(CompletedAdd add, Verdict verdict, Integer trendingRank, Integer addCount,
                         OffsetDateTime snapshotAt) {}

    /** One roster's counts: {@code judged} is TRENDING plus NOT_TRENDING (NOT_JUDGED is in neither). */
    public record Tally(int rosterId, int trending, int judged) {}

    /**
     * @param adds        every completed add for the league-season, any type
     * @param headers     the sport's stored snapshot headers covering the pickups' times
     * @param ranks       snapshot id to player id to that player's rank row, already filtered to
     *                    {@code rank <= TOP_N} and to the picked-up players
     * @param throughWeek the award's scored window; an add after it is dropped
     * @param eligible    a player id this returns {@code false} for (a football team defense) is dropped
     * @return one entry per counted pickup; dropped adds appear nowhere, so the size is the
     *         card's {@code totalPickups}
     */
    public static List<Judged> judge(List<CompletedAdd> adds, List<TrendingSnapshotHeader> headers,
                                     Map<Long, Map<String, TrendingSnapshotRank>> ranks, int throughWeek,
                                     Predicate<String> eligible) {
        List<TrendingSnapshotHeader> usable = headers.stream().filter(h -> h.entryCount() > 0).toList();
        List<Judged> out = new ArrayList<>();
        for (CompletedAdd a : adds) {
            if (!PickupRules.isCountedPickup(a) || a.week() > throughWeek) continue;
            if (!eligible.test(a.playerId())) continue;
            TrendingSnapshotHeader h = a.createdAt() == null ? null : judgingSnapshot(a.createdAt(), usable);
            if (h == null) {
                out.add(new Judged(a, Verdict.NOT_JUDGED, null, null, null));
                continue;
            }
            TrendingSnapshotRank r = ranks.getOrDefault(h.id(), Map.of()).get(a.playerId());
            if (r != null && r.rank() <= TOP_N) {
                out.add(new Judged(a, Verdict.TRENDING, r.rank(), r.addCount(), h.fetchedAt()));
            } else {
                out.add(new Judged(a, Verdict.NOT_TRENDING, null, null, h.fetchedAt()));
            }
        }
        return out;
    }

    /** After-first (research R1, F3): earliest in {@code [P, P+lookback]}, else latest in {@code [P-lookback, P)}. */
    private static TrendingSnapshotHeader judgingSnapshot(Instant p, List<TrendingSnapshotHeader> usable) {
        TrendingSnapshotHeader after = null;
        TrendingSnapshotHeader before = null;
        for (TrendingSnapshotHeader h : usable) { // do not rely on the caller's ordering
            Instant s = h.fetchedAt().toInstant();
            Duration lookback = Duration.ofHours(h.lookbackHours());
            if (!s.isBefore(p)) {
                if (!s.isAfter(p.plus(lookback)) && (after == null || s.isBefore(after.fetchedAt().toInstant()))) after = h;
            } else if (!s.isBefore(p.minus(lookback))
                    && (before == null || s.isAfter(before.fetchedAt().toInstant()))) {
                before = h;
            }
        }
        return after != null ? after : before;
    }

    /** Per-roster counts over {@link #judge}'s output; a roster with only NOT_JUDGED pickups is absent. */
    public static Map<Integer, Tally> tally(List<Judged> judged) {
        Map<Integer, int[]> acc = new TreeMap<>();
        for (Judged j : judged) {
            if (j.verdict() == Verdict.NOT_JUDGED) continue;
            int[] c = acc.computeIfAbsent(j.add().rosterId(), k -> new int[2]);
            c[1]++;
            if (j.verdict() == Verdict.TRENDING) c[0]++;
        }
        Map<Integer, Tally> out = new TreeMap<>();
        acc.forEach((roster, c) -> out.put(roster, new Tally(roster, c[0], c[1])));
        return out;
    }
}
