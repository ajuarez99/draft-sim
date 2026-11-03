package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 053 T007: Monday-Sunday weeks from opening night, empty weeks skipped. */
class HistoricalWeeksTest {

    private static final LocalDate OPENER = LocalDate.of(2019, 10, 22); // a Tuesday
    private static final LocalDate END = LocalDate.of(2020, 3, 11);

    @Test
    void aTuesdayOpenerLandsInWeekOneAndTheFollowingMondayStartsWeekTwo() {
        HistoricalWeeks w = HistoricalWeeks.of(List.of(
                OPENER, LocalDate.of(2019, 10, 27), LocalDate.of(2019, 10, 28)), OPENER, END);
        assertEquals(1, w.weekOf(OPENER).getAsInt());
        assertEquals(1, w.weekOf(LocalDate.of(2019, 10, 27)).getAsInt(), "Sunday is still week 1");
        assertEquals(2, w.weekOf(LocalDate.of(2019, 10, 28)).getAsInt(), "Monday starts week 2");
        assertEquals(2, w.weekCount());
    }

    @Test
    void anEmptyCalendarWeekDoesNotConsumeANumber() {
        // Games in the weeks of Oct 21, then nothing the week of Oct 28, then the week of Nov 4.
        HistoricalWeeks w = HistoricalWeeks.of(List.of(
                OPENER, LocalDate.of(2019, 11, 5)), OPENER, END);
        assertEquals(1, w.weekOf(OPENER).getAsInt());
        assertEquals(2, w.weekOf(LocalDate.of(2019, 11, 5)).getAsInt(), "the empty week is skipped");
        assertEquals(2, w.weekCount());
    }

    @Test
    void datesAfterTheCutoffHaveNoWeek() {
        HistoricalWeeks w = HistoricalWeeks.of(List.of(
                OPENER, LocalDate.of(2020, 3, 11), LocalDate.of(2020, 3, 12)), OPENER, END);
        assertTrue(w.weekOf(LocalDate.of(2020, 3, 11)).isPresent(), "the cutoff date itself counts");
        assertTrue(w.weekOf(LocalDate.of(2020, 3, 12)).isEmpty());
        assertEquals(2, w.weekCount(), "the bubble game opens no week");
    }

    @Test
    void anUnseenDateInsideAnEmptyWeekHasNoWeek() {
        HistoricalWeeks w = HistoricalWeeks.of(List.of(OPENER, LocalDate.of(2019, 11, 5)), OPENER, END);
        assertTrue(w.weekOf(LocalDate.of(2019, 10, 30)).isEmpty());
    }
}
