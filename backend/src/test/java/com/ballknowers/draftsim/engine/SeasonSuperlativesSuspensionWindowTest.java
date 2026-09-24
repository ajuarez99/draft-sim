package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for two bugs found in specs/008-season-superlatives
 * live verification (2026-09-23):
 *
 * <ol>
 *   <li>{@code suspensionWeeksObserved} must only list captured weeks inside
 *   THIS payload's own scored window (1..throughWeek), not bounded by
 *   {@code regularSeasonEnd} (the playoff cutoff, which sits far ahead of
 *   how many weeks are actually scored). Live: NFL 2026 was scored through
 *   week 2, but a capture already existed for week 3 (ingest captures
 *   whatever week Sleeper's {@code /state/nfl} reports "now"), and bounding
 *   by regularSeasonEnd let week 3 leak into the payload while the page's
 *   own window said week 2.</li>
 *   <li>UNETHICAL's emptyReason must name which source actually came up
 *   empty, not always blame "no suspension captured" -- and the commissioner
 *   clause is appended only when that list is genuinely empty, never merely
 *   because nothing in it qualified yet.</li>
 * </ol>
 */
class SeasonSuperlativesSuspensionWindowTest {

    // ------------------------------------------------- boundedCapturedWeeks

    @Test
    void weekPastThroughWeekIsExcludedEvenThoughItWasCaptured() {
        // The exact live shape: captured weeks 1, 2 and 3; scored through week 2.
        List<Integer> observed = SeasonSuperlativesService.boundedCapturedWeeks(Set.of(1, 2, 3), 2);

        assertEquals(List.of(1, 2), observed, "week 3 was captured but is past throughWeek -- must not appear");
    }

    @Test
    void weekZeroIsNeverReportedEvenIfSomehowCaptured() {
        List<Integer> observed = SeasonSuperlativesService.boundedCapturedWeeks(Set.of(0, 1), 5);

        assertEquals(List.of(1), observed, "week 0 is Sleeper's offseason marker, never a real fantasy week");
    }

    @Test
    void resultIsSortedRegardlessOfInputOrder() {
        List<Integer> observed = SeasonSuperlativesService.boundedCapturedWeeks(Set.of(4, 1, 3, 2), 4);

        assertEquals(List.of(1, 2, 3, 4), observed);
    }

    @Test
    void noCapturedWeeksIsEmpty() {
        assertTrue(SeasonSuperlativesService.boundedCapturedWeeks(Set.of(), 10).isEmpty());
    }

    // ------------------------------------------------- unethicalEmptyReason

    @Test
    void captureExistsButNothingQualifiesAndConductListEmpty() {
        String reason = SeasonSuperlativesService.unethicalEmptyReason(true, true);

        assertEquals("no rostered player has been suspended in a tracked week, and the commissioner's list is empty",
                reason);
    }

    @Test
    void captureExistsAndConductListHasEntriesThatJustDontQualifyYet() {
        String reason = SeasonSuperlativesService.unethicalEmptyReason(true, false);

        assertEquals("no rostered player has been suspended in a tracked week", reason,
                "the commissioner clause must not be appended when that list isn't actually empty");
    }

    @Test
    void noCaptureAtAllAndConductListEmpty() {
        String reason = SeasonSuperlativesService.unethicalEmptyReason(false, true);

        assertEquals("suspension tracking hasn't covered a scored week yet, and the commissioner's list is empty",
                reason);
    }

    @Test
    void noCaptureAtAllButConductListHasEntries() {
        String reason = SeasonSuperlativesService.unethicalEmptyReason(false, false);

        assertEquals("suspension tracking hasn't covered a scored week yet", reason);
    }
}
