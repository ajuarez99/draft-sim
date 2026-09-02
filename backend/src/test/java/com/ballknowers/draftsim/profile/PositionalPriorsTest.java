package com.ballknowers.draftsim.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bucketing is the whole of the normalization change, so it is worth
 * pinning precisely — including the property that made the change safe to
 * make: on a 15-round league it reproduces the round-keyed table it replaced,
 * exactly, for every league size.
 */
class PositionalPriorsTest {

    private static final int BUCKETS = PositionalPriors.DEFAULT_BUCKETS;   // 15

    @ParameterizedTest
    @ValueSource(ints = {8, 10, 12, 14})
    void atFifteenBucketsAndFifteenRoundsABucketIsExactlyARound(int teams) {
        int rounds = 15;
        int totalPicks = teams * rounds;
        for (int pickNo = 1; pickNo <= totalPicks; pickNo++) {
            int expectedRound = ((pickNo - 1) / teams) + 1;
            assertEquals(expectedRound - 1, PositionalPriors.bucketOf(pickNo, totalPicks, BUCKETS),
                    "pick " + pickNo + " of a " + teams + "-team draft");
        }
    }

    /**
     * The reason for the change. Fitting happens on 12-team drafts and
     * simulation runs at 14, so "the same point in the draft" has to mean the
     * same cell in both, which a raw round number only manages by accident.
     */
    @Test
    void theSameFractionOfTwoDifferentlySizedDraftsLandsInTheSameBucket() {
        // The opening pick, the midpoint, and the last pick.
        assertEquals(PositionalPriors.bucketOf(1, 180, BUCKETS),
                PositionalPriors.bucketOf(1, 210, BUCKETS));
        assertEquals(PositionalPriors.bucketOf(91, 180, BUCKETS),
                PositionalPriors.bucketOf(106, 210, BUCKETS));
        assertEquals(PositionalPriors.bucketOf(180, 180, BUCKETS),
                PositionalPriors.bucketOf(210, 210, BUCKETS));
    }

    @Test
    void bucketsStayInRangeAtTheEdgesAndUnderNonsenseInput() {
        assertEquals(0, PositionalPriors.bucketOf(1, 210, BUCKETS));
        assertEquals(BUCKETS - 1, PositionalPriors.bucketOf(210, 210, BUCKETS));
        // A pick past the end of the draft (a caller bug, but not a crash).
        assertEquals(BUCKETS - 1, PositionalPriors.bucketOf(999, 210, BUCKETS));
        assertEquals(0, PositionalPriors.bucketOf(5, 0, BUCKETS));
        assertEquals(0, PositionalPriors.bucketOf(5, 210, 0));
    }

    /**
     * A round count other than 15 is where the old keying actually broke: a
     * 20-round league's round 13 is nowhere near the same part of the draft as
     * a 15-round league's, and the fitted table has no cell past 15 at all.
     */
    @Test
    void aLongerDraftStillSpansExactlyTheSameBuckets() {
        int totalPicks = 14 * 20;
        assertEquals(0, PositionalPriors.bucketOf(1, totalPicks, BUCKETS));
        assertEquals(BUCKETS - 1, PositionalPriors.bucketOf(totalPicks, totalPicks, BUCKETS));
        // Round 13 of 20 is only ~63% of the way in, not 87% as it is at 15 rounds.
        assertEquals(9, PositionalPriors.bucketOf(13 * 14, totalPicks, BUCKETS));
        assertEquals(12, PositionalPriors.bucketOf(13 * 14, 14 * 15, BUCKETS));
    }

    @Test
    void theUniformTableAnswersForEveryBucket() {
        PositionalPriors flat = PositionalPriors.uniform();
        double first = flat.probability(0, com.ballknowers.draftsim.domain.Position.WR);
        assertEquals(first, flat.probability(BUCKETS - 1, com.ballknowers.draftsim.domain.Position.WR), 1e-12);
        assertTrue(first > 0);
        assertEquals(BUCKETS, flat.buckets());
    }
}
