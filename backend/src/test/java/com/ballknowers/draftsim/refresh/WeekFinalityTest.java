package com.ballknowers.draftsim.refresh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeekFinalityTest {

    @Test
    void weekFetchedWhileItWasTheLastScoredLegIsNotFinal() {
        assertFalse(WeekFinality.isFinal(10, 10));
    }

    @Test
    void weekFetchedAfterTheLeagueMovedOnIsFinal() {
        assertTrue(WeekFinality.isFinal(10, 11));
    }

    @Test
    void theLastScoredWeekOfACompleteLeagueIsNotFinal() {
        // Amended after review: a league status of "complete" no longer freezes the
        // last scored week (the championship week) at the first post-completion fetch;
        // it keeps being refetched until loaded_complete, i.e. through stat corrections.
        assertFalse(WeekFinality.isFinal(10, 10));
    }

    @Test
    void weekZeroOrBelowIsNeverFinal() {
        assertFalse(WeekFinality.isFinal(0, 5));
        assertFalse(WeekFinality.isFinal(-1, 5));
    }
}
