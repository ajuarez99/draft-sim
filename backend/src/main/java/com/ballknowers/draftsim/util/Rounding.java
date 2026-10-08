package com.ballknowers.draftsim.util;

/**
 * The one implementation of "round to N decimal places" for numbers the API returns.
 * Before specs/021-codebase-cleanup, 17 files each kept a private copy.
 *
 * <p>This is half-up via {@link Math#round} on {@code x * 10^n}, exactly the formula
 * those copies used. It is <b>not</b> {@code BigDecimal} rounding, and that is
 * deliberate. Values that aren't exactly representable round the way they always
 * have, e.g. {@code round2(1.005) == 1.0}, not 1.01. Changing that would move numbers
 * on screen. RoundingTest pins it.
 *
 * <p>An inline {@code Math.round(v * 1000.0 / total) / 1000.0} is a different function
 * from {@code round3(v / total)}, because the division happens after the scaling.
 * Leave those inline (see PlayoffOddsService).
 */
public final class Rounding {

    private Rounding() {}

    public static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    public static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    public static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    public static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    /** For a place count chosen at runtime. {@code Math.pow(10, n)} is exact for these small n. */
    public static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    /**
     * {@link #round2(double)} that lets a null through. It has its own name on purpose.
     * As an overload of {@code round2}, every call passing a {@code Double} object
     * would bind to it rather than unboxing into the primitive version. A call that
     * fails loudly on null today would then return null quietly.
     */
    public static Double round2OrNull(Double v) {
        return v == null ? null : round2(v);
    }
}
