package com.ballknowers.draftsim.store;

/**
 * An explicit ceiling on which weeks a score/pairing query reads, or the
 * absence of one -- never a default (specs/008-season-superlatives T010,
 * memory "optional params that encode rules").
 *
 * <p>A defaulted week bound would let a caller who forgot it silently mean
 * "all weeks," which is the bug shape this repo has shipped three times under
 * different names. There is deliberately no overload without this parameter:
 * every caller states {@link #ALL_WEEKS} or {@link #through} explicitly, so a
 * new call site is a choice rather than an accident.
 *
 * @param throughWeek the highest week to include, inclusive, or {@code null}
 *                     for no ceiling at all.
 */
public record WeekBound(Integer throughWeek) {

    /** No ceiling: every stored week counts. */
    public static final WeekBound ALL_WEEKS = new WeekBound(null);

    /** A ceiling at {@code week}, inclusive. */
    public static WeekBound through(int week) {
        return new WeekBound(week);
    }
}
