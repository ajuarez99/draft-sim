package com.ballknowers.draftsim.util;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * specs/021-codebase-cleanup T017. {@link Rounding} replaces 21 private helpers in 17
 * files. Before that swap, this test proves the replacement returns the same double,
 * bit for bit, as the formula those helpers used. The old formulas are pasted below as
 * the oracle, so they outlive the code being deleted.
 *
 * <p>The edge values include ones where a "better" rounding (BigDecimal HALF_UP) would
 * differ. 1.005 is not exactly representable, and {@code Math.round(1.005 * 100)} rounds
 * it down to 1.0. That is today's behavior, and the point is to keep it.
 */
class RoundingTest {

    // The pre-refactor helpers, verbatim.
    private static double oldRound1(double d) { return Math.round(d * 10.0) / 10.0; }
    private static double oldRound2(double d) { return Math.round(d * 100.0) / 100.0; }
    private static double oldRound3(double d) { return Math.round(d * 1000.0) / 1000.0; }
    private static double oldRound4(double d) { return Math.round(d * 10000.0) / 10000.0; }
    private static double oldRoundPlaces(double v, int places) { double f = Math.pow(10, places); return Math.round(v * f) / f; }
    private static Double oldRound2Boxed(Double v) { return v == null ? null : Math.round(v * 100.0) / 100.0; }

    private static final double[] EDGES = {
            1.005, 2.675, 0.125, 0.5, -0.5, 1.5, -1.5, 0.0, -0.0, 0.045, 1.0e-9, -1.0e-9,
            123456.785, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, 92233720368547.76,
    };

    private static void assertSame(DoubleUnaryOperator expected, DoubleUnaryOperator actual, String name) {
        Random r = new Random(21);
        for (int i = 0; i < 100_000; i++) {
            double x = (r.nextDouble() * 2 - 1) * 1e6;
            check(expected, actual, name, x);
        }
        for (double x : EDGES) check(expected, actual, name, x);
    }

    private static void check(DoubleUnaryOperator expected, DoubleUnaryOperator actual, String name, double x) {
        long e = Double.doubleToRawLongBits(expected.applyAsDouble(x));
        long a = Double.doubleToRawLongBits(actual.applyAsDouble(x));
        assertEquals(e, a, () -> name + "(" + x + "): expected " + expected.applyAsDouble(x) + " got " + actual.applyAsDouble(x));
    }

    @Test
    void fixedPlaceHelpersMatchTheOldFormulasBitForBit() {
        assertSame(RoundingTest::oldRound1, Rounding::round1, "round1");
        assertSame(RoundingTest::oldRound2, Rounding::round2, "round2");
        assertSame(RoundingTest::oldRound3, Rounding::round3, "round3");
        assertSame(RoundingTest::oldRound4, Rounding::round4, "round4");
    }

    @Test
    void placesHelperMatchesTheOldPowFormulaForEveryPlaceCountInUse() {
        for (int places = 0; places <= 4; places++) {
            int p = places;
            assertSame(v -> oldRoundPlaces(v, p), v -> Rounding.round(v, p), "round(v," + p + ")");
        }
    }

    @Test
    void placesHelperAgreesWithTheFixedHelpers() {
        // LeagueAnalysisService and PlayerTrendsService used the pow form; everyone else
        // used the literal. They must coincide, or the merge would change one family.
        assertSame(Rounding::round2, v -> Rounding.round(v, 2), "round(v,2) vs round2");
        assertSame(Rounding::round4, v -> Rounding.round(v, 4), "round(v,4) vs round4");
    }

    @Test
    void round2OrNullPreservesNullAndOtherwiseMatchesTheOldBoxedHelper() {
        assertNull(Rounding.round2OrNull(null));
        assertSame(v -> oldRound2Boxed(v), v -> Rounding.round2OrNull(v), "round2OrNull");
    }

    @Test
    void theKnownNonRepresentableValuesRoundTheWayTheyAlwaysHave() {
        assertEquals(1.0, Rounding.round2(1.005));   // not 1.01: 1.005 * 100.0 is 100.49999999999999
        // 2.675 is not exactly representable either, but 2.675 * 100.0 lands on 267.5
        // and rounds up. That was measured by this test: it was first written expecting
        // 2.67, as a guess, and it failed.
        assertEquals(2.68, Rounding.round2(2.675));
        assertEquals(0.13, Rounding.round2(0.125));  // exactly representable, half-up
        assertEquals(0.0, Rounding.round2(-0.001));  // Math.round(-0.1) is the long 0, so +0.0, never -0.0
    }
}
