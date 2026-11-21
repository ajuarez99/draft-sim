package com.ballknowers.draftsim.historical;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The running number of a Redraft game of the day ("daily #8"), spec 078 (specs/078-redraft-daily-number/).
 *
 * <p>The one definition, shared by both sports: day 1 is {@link #FIRST_DAY}, and every calendar day after it is
 * the next number, whether or not anyone dealt or played that day's puzzle. It comes from the date alone, never
 * from {@code historical_daily}, so the number is the same on every server and database, and a day nobody asked
 * for still takes its number (the way a calendar does).
 */
public final class DailyNumber {

    /**
     * 2026-11-14, the US Eastern day spec 071 (game of the day) was pushed to main and auto-deployed. Not read
     * from production's {@code historical_daily}: that check was not run, so if prod's first puzzle row is a
     * later date, day 1 here is still the launch date, by choice.
     */
    public static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 14);

    private DailyNumber() {}

    /** The daily number of {@code date} (a US Eastern calendar day); null for null or a date before day 1. */
    public static Integer of(LocalDate date) {
        if (date == null || date.isBefore(FIRST_DAY)) return null;
        return Math.toIntExact(ChronoUnit.DAYS.between(FIRST_DAY, date) + 1);
    }
}
