package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 063 T004: how a season is cut into regular season and playoffs. */
class SeasonFormatTest {

    @Test
    void aLongSeasonKeepsTheConfiguredRegularSeasonAndLeavesTheRestUnused() {
        SeasonFormat f = SeasonFormat.of(25, 19, 12);
        assertEquals(19, f.regularSeasonWeeks());
        assertEquals(List.of(20, 21, 22), f.playoffWeeks());
        assertEquals(3, f.unusedWeeks());
        assertFalse(f.shortened());
        assertEquals(2, f.version());
    }

    @Test
    void exactlyEnoughWeeksLeavesNothingUnused() {
        SeasonFormat f = SeasonFormat.of(22, 19, 12);
        assertEquals(19, f.regularSeasonWeeks());
        assertEquals(0, f.unusedWeeks());
        assertFalse(f.shortened());
    }

    @Test
    void oneWeekTooShortShrinksTheRegularSeason() {
        SeasonFormat f = SeasonFormat.of(21, 19, 12);
        assertEquals(18, f.regularSeasonWeeks());
        assertEquals(List.of(19, 20, 21), f.playoffWeeks());
        assertTrue(f.shortened());
        assertEquals(0, f.unusedWeeks());
    }

    @Test
    void theShrunkRegularSeasonFloorsAtOneFullRoundRobin() {
        SeasonFormat f = SeasonFormat.of(14, 19, 12);
        assertEquals(11, f.regularSeasonWeeks());
        assertTrue(f.shortened());
        SeasonFormat none = SeasonFormat.of(13, 19, 12);
        assertFalse(none.hasPlayoffs());
        assertEquals(13, none.regularSeasonWeeks());
        assertEquals(List.of(), none.playoffWeeks());
        assertEquals(0, none.unusedWeeks());
        assertFalse(none.shortened());
    }

    @Test
    void footballShape() {
        SeasonFormat f = SeasonFormat.of(18, 14, 12);
        assertEquals(14, f.regularSeasonWeeks());
        assertEquals(List.of(15, 16, 17), f.playoffWeeks());
        assertEquals(1, f.unusedWeeks());
        SeasonFormat g = SeasonFormat.of(17, 14, 12);
        assertEquals(0, g.unusedWeeks());
        assertEquals(17, g.lastPlayedWeek());
        assertFalse(g.shortened());
    }

    @Test
    void tooFewTeamsOrWeeksMeansNoPlayoffs() {
        SeasonFormat f = SeasonFormat.of(3, 19, 4);
        assertFalse(f.hasPlayoffs());
        assertEquals(3, f.regularSeasonWeeks());
        assertEquals(3, f.lastPlayedWeek());
        // enough weeks, but a six-team bracket needs six teams
        assertFalse(SeasonFormat.of(14, 19, 4).hasPlayoffs());
        assertFalse(SeasonFormat.of(30, 19, 4).hasPlayoffs(), "even a long season has no bracket for four teams");
    }
}
