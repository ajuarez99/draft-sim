package com.ballknowers.draftsim.engine;

/**
 * Where a season stands relative to "too early to read much into" (spec 013 T069).
 * One declaration, read by the superlatives' early flag and by the letter grades'
 * {@code gradesEarly}, so the two can never disagree about what "early" means.
 */
public final class SeasonWindow {

    /**
     * Hand-set, ARBITRARY, not fitted to anything (superlatives research R6): fewer than this
     * many scored weeks and every rate-based read is mostly noise.
     */
    public static final int EARLY_THRESHOLD_WEEKS = 4;

    private SeasonWindow() {}

    /** True while fewer than {@link #EARLY_THRESHOLD_WEEKS} weeks have been scored. */
    public static boolean isEarly(int weeksScored) {
        return weeksScored < EARLY_THRESHOLD_WEEKS;
    }
}
