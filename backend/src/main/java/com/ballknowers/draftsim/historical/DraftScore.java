package com.ballknowers.draftsim.historical;

/**
 * A finished Time Machine game in one number (spec 070): your whole-season points as a whole-number share of the
 * greedy hindsight draft's, rounded down. Above 100 when you beat it.
 *
 * <p>The web computes the same number from the frozen result ({@code draftScore} in
 * {@code web/src/historicalShare.ts}). That is two implementations of one rule, so both use the same expression
 * ({@code floor(100 * yours / hindsight)}) and are pinned to the same vectors in their tests. Change one, change
 * the other.
 */
public final class DraftScore {

    private DraftScore() {}

    /** Null when either total is missing, or there is no positive hindsight total to divide by. */
    public static Integer of(Double userPointsFor, Double hindsightPointsFor) {
        if (userPointsFor == null || hindsightPointsFor == null) return null;
        if (!(hindsightPointsFor > 0) || userPointsFor.isNaN() || userPointsFor.isInfinite()) return null;
        return (int) Math.floor((100 * userPointsFor) / hindsightPointsFor);
    }
}
