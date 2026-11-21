package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Spec 078: the running number of a game of the day, exact values. */
class DailyNumberTest {

    @Test
    void dayOneIsTheLaunchDateAndEachCalendarDayIsTheNext() {
        assertEquals(1, DailyNumber.of(LocalDate.of(2026, 11, 14)));
        assertEquals(2, DailyNumber.of(LocalDate.of(2026, 11, 15)));
        assertEquals(8, DailyNumber.of(LocalDate.of(2026, 11, 21)));
        assertEquals(18, DailyNumber.of(LocalDate.of(2026, 12, 1)), "across a month end");
        assertEquals(49, DailyNumber.of(LocalDate.of(2027, 1, 1)), "across a year end");
        assertEquals(366, DailyNumber.of(LocalDate.of(2027, 11, 14)), "a year later (2027 is not a leap year)");
    }

    @Test
    void beforeDayOneAndNoDateHaveNoNumber() {
        assertNull(DailyNumber.of(LocalDate.of(2026, 11, 13)));
        assertNull(DailyNumber.of(LocalDate.of(1895, 3, 10)));
        assertNull(DailyNumber.of(null));
    }
}
