package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.domain.Position;

import java.util.EnumMap;
import java.util.Map;

/**
 * P(position | how far into the draft we are), fit from every completed draft
 * that has been ingested and smoothed with Dirichlet pseudo-counts.
 *
 * The conditioning variable is a <em>fraction of the draft</em> bucket, not a
 * round number. Round index does not transfer across league sizes: round 3 is
 * picks 21-30 in a 10-team league and 29-42 in a 14-team one, so a table keyed
 * on round silently pools different regions of the board the moment two sizes
 * are mixed — and the fitted table is built from 12-team drafts while the
 * league being simulated is 14. See claude/borrowed-drafts.md, "Two confounds
 * that will bite".
 *
 * With {@code buckets} equal to a league's round count the buckets ARE the
 * rounds, so on today's data — every ingested draft is 15 rounds — this is
 * numerically identical to the round-keyed table it replaces. It stops being
 * identical the moment a draft has a different round count or an ad-hoc
 * league is simulated at a size nothing was fit on, which is the point.
 *
 * The design doc wants this fit on a large corpus of public drafts. That corpus
 * is not wired up, so this is fit on a handful of drafts instead and smoothed
 * heavily to compensate. With alpha = 8 and ~24 observations per (bucket,
 * position) cell, the prior is doing more work than the data. That is the
 * intended behavior at this sample size, not a bug, but it does mean this term
 * mostly encodes "kickers go late, QBs are spread out" rather than anything
 * specific to these managers.
 */
public final class PositionalPriors {

    /**
     * Buckets the draft is divided into. 15 so that a 15-round league — every
     * league this has ever been pointed at — buckets exactly one round per
     * cell, which is what makes this change a no-op on existing data.
     */
    public static final int DEFAULT_BUCKETS = 15;

    private final Map<Integer, Map<Position, Double>> byBucket;
    private final Map<Position, Double> overall;
    private final int observations;
    private final int buckets;

    PositionalPriors(Map<Integer, Map<Position, Double>> byBucket,
                     Map<Position, Double> overall,
                     int observations,
                     int buckets) {
        this.byBucket = byBucket;
        this.overall = overall;
        this.observations = observations;
        this.buckets = buckets;
    }

    /**
     * Which bucket {@code pickNo} falls in, for a draft of {@code totalPicks}
     * picks. 0-indexed. Integer arithmetic on purpose: at 15 buckets and 210
     * picks the boundaries land exactly on round boundaries, and a floating
     * point {@code (pickNo - 1) / totalPicks * buckets} does not reliably.
     */
    public int bucketOf(int pickNo, int totalPicks) {
        return bucketOf(pickNo, totalPicks, buckets);
    }

    /** Static form, for callers bucketing picks before a table exists to ask. */
    public static int bucketOf(int pickNo, int totalPicks, int buckets) {
        if (totalPicks <= 0 || buckets <= 0) return 0;
        long b = (long) (pickNo - 1) * buckets / totalPicks;
        return (int) Math.max(0, Math.min(buckets - 1, b));
    }

    public int buckets() {
        return buckets;
    }

    public double probability(int bucket, Position pos) {
        Map<Position, Double> table = byBucket.get(bucket);
        if (table == null) return overall.getOrDefault(pos, 1.0 / Position.values().length);
        return table.getOrDefault(pos, 1e-6);
    }

    /**
     * Log probability, which is what the scoring function adds. Kept separate so
     * the weight on this term stays interpretable: it is a log-odds nudge, not a
     * probability multiplier.
     *
     * @param bucket from {@link #bucketOf}, NOT a round number.
     */
    public double logProbability(int bucket, Position pos) {
        return Math.log(Math.max(probability(bucket, pos), 1e-9));
    }

    public int observations() {
        return observations;
    }

    public Map<Position, Double> forBucket(int bucket) {
        Map<Position, Double> t = byBucket.get(bucket);
        return t == null ? new EnumMap<>(overall) : new EnumMap<>(t);
    }

    public static PositionalPriors uniform() {
        Map<Position, Double> flat = new EnumMap<>(Position.class);
        for (Position p : Position.values()) flat.put(p, 1.0 / Position.values().length);
        return new PositionalPriors(Map.of(), flat, 0, DEFAULT_BUCKETS);
    }
}
