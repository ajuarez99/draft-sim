package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 064: the level curve and names, pinned to the spec's own examples. */
class LevelsTest {

    @Test
    void thresholdsMatchTheSpecExamples() {
        assertEquals(0, Levels.startXp(1));
        assertEquals(100, Levels.startXp(2));
        assertEquals(300, Levels.startXp(3));
        assertEquals(1000, Levels.startXp(5));
        assertEquals(4500, Levels.startXp(10));
    }

    @Test
    void levelRisesExactlyAtTheThreshold() {
        assertEquals(1, Levels.levelFor(0));
        assertEquals(1, Levels.levelFor(99));
        assertEquals(2, Levels.levelFor(100));
        assertEquals(2, Levels.levelFor(299));
        assertEquals(3, Levels.levelFor(300));
        assertEquals(3, Levels.levelFor(599));
        assertEquals(4, Levels.levelFor(600));
        assertEquals(5, Levels.levelFor(1000));
        assertEquals(10, Levels.levelFor(4500));
        assertEquals(9, Levels.levelFor(4499));
        assertEquals(1, Levels.levelFor(-50), "negative xp is level 1");
    }

    @Test
    void levelAndStartAreConsistentForEveryLevel() {
        for (int n = 1; n <= 40; n++) {
            assertEquals(n, Levels.levelFor(Levels.startXp(n)));
            assertEquals(n, Levels.levelFor(Levels.startXp(n + 1) - 1));
            assertTrue(Levels.startXp(n + 1) > Levels.startXp(n), "strictly increasing, no cap");
        }
    }

    @Test
    void namesFollowTheBands() {
        assertEquals("Rookie", Levels.name(1));
        assertEquals("Rookie", Levels.name(2));
        assertEquals("Starter", Levels.name(3));
        assertEquals("Starter", Levels.name(4));
        assertEquals("Veteran", Levels.name(5));
        assertEquals("Veteran", Levels.name(7));
        assertEquals("All-Star", Levels.name(8));
        assertEquals("All-Star", Levels.name(11));
        assertEquals("MVP", Levels.name(12));
        assertEquals("MVP", Levels.name(16));
        assertEquals("Hall of Famer", Levels.name(17));
        assertEquals("Hall of Famer", Levels.name(99));
    }

    @Test
    void levelBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Levels.startXp(0));
    }
}
