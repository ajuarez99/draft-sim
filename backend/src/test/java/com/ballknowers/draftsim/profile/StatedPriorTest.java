package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.config.ShrinkageProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stated tendency is the shrinkage TARGET, not an override. These pin the
 * behaviour at both ends: with no history the user's number is used exactly, and
 * with history it blends rather than being ignored or winning outright.
 */
class StatedPriorTest {

    private final ShrinkageProperties shrink = new ShrinkageProperties(4.0);

    @Test
    void withNoHistoryTheStatedValueIsUsedExactly() {
        // fantasy(heart): zero drafts observed, so the seat behaves exactly as told
        assertEquals(10.0, shrink.shrink(0.0, 10.0, 0), 1e-9);
        assertEquals(-6.0, shrink.shrink(99.0, -6.0, 0), 1e-9);
    }

    @Test
    void withTwoDraftsItIsOneThirdDataAndTwoThirdsStated() {
        double observed = 1.0, stated = 10.0;
        assertEquals(observed / 3 + stated * 2 / 3, shrink.shrink(observed, stated, 2), 1e-9);
    }

    @Test
    void theBlendAlwaysSitsBetweenTheTwo() {
        double blended = shrink.shrink(1.0, 10.0, 2);
        assertTrue(blended > 1.0 && blended < 10.0, "got " + blended);
    }

    @Test
    void withNothingStatedTheTargetIsTheLeagueMeanAsBefore() {
        // league mean 0.0 -> identical to the pre-manual-tendencies behaviour
        assertEquals(1.0 / 3, shrink.shrink(1.0, 0.0, 2), 1e-9);
    }

    @Test
    void moreHistoryPullsFurtherFromTheStatedValue() {
        double two = shrink.shrink(0.0, 10.0, 2);
        double eight = shrink.shrink(0.0, 10.0, 8);
        assertTrue(eight < two, "more evidence should move away from the prior");
    }
}
