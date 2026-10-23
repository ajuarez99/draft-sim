package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 044 T011: the stopping recursion against a hand computation, and the pinned quantile method. */
class LockThresholdsTest {

    /**
     * margins 0.0 0.2 0.4 0.6 0.8 (mean 0.4), p = 0.1:
     * <pre>
     *   V1 = 0.9 * 0.4 - 0.1                         = 0.26
     *   V2 = mean(max(m, 0.26))  = (0.26+0.26+0.4+0.6+0.8) / 5     = 2.32  / 5 = 0.464
     *   V3 = mean(max(m, 0.464)) = (0.464*3 + 0.6 + 0.8) / 5       = 2.792 / 5 = 0.5584
     *   V4 = mean(max(m, 0.5584)) = (0.5584*3 + 0.6 + 0.8) / 5     = 3.0752/ 5 = 0.61504
     * </pre>
     */
    @Test
    void theRecursionMatchesAHandComputedFiveValueFixture() {
        double[] v = LockThresholds.fit(new double[]{0.0, 0.2, 0.4, 0.6, 0.8}, 0.1, 4);
        assertEquals(5, v.length);
        assertTrue(Double.isNaN(v[0]), "no games left, no decision");
        assertEquals(0.26, v[1], 1e-12);
        assertEquals(0.464, v[2], 1e-12);
        assertEquals(0.5584, v[3], 1e-12);
        assertEquals(0.61504, v[4], 1e-12);
    }

    @Test
    void withNoMissedDefaultsTheLastGameIsWorthItsMean() {
        double[] v = LockThresholds.fit(new double[]{0.0, 0.2, 0.4, 0.6, 0.8}, 0.0, 2);
        assertEquals(0.4, v[1], 1e-12);
    }

    @Test
    void thresholdsNeverFallAsMoreGamesRemain() {
        double[] v = LockThresholds.fit(new double[]{-0.5, -0.1, 0.0, 0.1, 0.3, 0.3, 0.9, 1.4}, 0.03, 4);
        for (int k = 1; k < 4; k++) assertTrue(v[k + 1] >= v[k], "V" + (k + 1) + " >= V" + k);
    }

    @Test
    void emptyMarginsGiveNoThresholds() {
        double[] v = LockThresholds.fit(new double[0], 0.05, 3);
        assertEquals(4, v.length);
        for (double x : v) assertTrue(Double.isNaN(x));
    }

    // Expected values from: python -c "import statistics; print(statistics.quantiles(DATA, n=4))"
    //   n=10: [0.17500000000000002, 0.4, 0.6499999999999999]
    //   n=11: [0.2, 0.45, 0.7]            (the default method is 'exclusive')

    @Test
    void quantilesAreTheExclusiveMethodAtTenValues() {
        double[] q = LockThresholds.quantiles(new double[]{0.1, 0.35, 0.2, 0.9, 0.5, 0.45, 0.8, 0.05, 0.6, 0.3});
        assertEquals(0.175, q[0], 1e-12);
        assertEquals(0.4, q[1], 1e-12);
        assertEquals(0.65, q[2], 1e-12);
    }

    @Test
    void quantilesAreTheExclusiveMethodAtElevenValues() {
        double[] q = LockThresholds.quantiles(new double[]{0.1, 0.35, 0.2, 0.9, 0.5, 0.45, 0.8, 0.05, 0.6, 0.3, 0.7});
        assertEquals(0.2, q[0], 1e-12);
        assertEquals(0.45, q[1], 1e-12);
        assertEquals(0.7, q[2], 1e-12);
    }

    @Test
    void quantilesClampAtTheEdgesAndAreUndefinedBelowTwoValues() {
        double[] two = LockThresholds.quantiles(new double[]{1.0, 2.0});       // python: [0.75, 1.5, 2.25]
        assertEquals(0.75, two[0], 1e-12);
        assertEquals(1.5, two[1], 1e-12);
        assertEquals(2.25, two[2], 1e-12);
        assertNull(LockThresholds.quantiles(new double[]{1.0}));
        assertNull(LockThresholds.quantiles(new double[0]));
    }
}
