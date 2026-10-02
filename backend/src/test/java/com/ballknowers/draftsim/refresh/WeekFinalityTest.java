package com.ballknowers.draftsim.refresh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeekFinalityTest {

    @Test
    void weekFetchedWhileItWasTheLastScoredLegIsNotFinal() {
        // NBA mid-week (R14): last_scored_leg and leg both name the week being played.
        assertFalse(WeekFinality.isFinal(10, 10, 10));
    }

    @Test
    void weekFetchedAfterTheLeagueMovedOnIsFinal() {
        assertTrue(WeekFinality.isFinal(10, 11, 11));
    }

    @Test
    void anNflWeekIsFinalOnceScoredAndTheLeagueHasMovedOn() {
        // Measured 2026-10-02 on (Foot) Ball Knowers 2026: leg 4, last_scored_leg 3,
        // week 4 already scoring. Week 3 is decided; Sleeper's own W-L counts it.
        assertTrue(WeekFinality.isFinal(3, 3, 4));
        assertFalse(WeekFinality.isFinal(4, 3, 4), "the week being played is never final");
    }

    @Test
    void theLastScoredWeekOfACompleteLeagueIsNotFinal() {
        // Amended after review: a league status of "complete" no longer freezes the
        // last scored week (the championship week) at the first post-completion fetch;
        // it keeps being refetched until loaded_complete, i.e. through stat corrections.
        // Complete seasons read leg == last_scored_leg (NFL 2025: 17/17, NBA 2025: 21/21).
        assertFalse(WeekFinality.isFinal(17, 17, 17));
    }

    @Test
    void aMissingLegFallsBackToTheOriginalRule() {
        assertFalse(WeekFinality.isFinal(3, 3, 0));
        assertTrue(WeekFinality.isFinal(3, 4, 0));
    }

    @Test
    void weekZeroOrBelowIsNeverFinal() {
        assertFalse(WeekFinality.isFinal(0, 5, 6));
        assertFalse(WeekFinality.isFinal(-1, 5, 6));
    }

    @Test
    void aFinalWeekIsStillRefetchedWhileItIsTheLastScoredWeek() {
        // NFL week 3 counts from the moment leg moves to 4, but a stat correction made
        // during week 4 must still land, so it is refetched until last_scored_leg passes it.
        assertFalse(WeekFinality.mayStopRefetching(true, 3, 3));
        assertTrue(WeekFinality.mayStopRefetching(true, 3, 4));
        assertFalse(WeekFinality.mayStopRefetching(false, 3, 4), "a non-final week is always refetched");
    }
}
