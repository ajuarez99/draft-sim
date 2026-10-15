package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** specs/034 T008 (plan.md F17): a missing or partial block is not loaded; a bad value fails startup. */
class RivalryPropertiesTest {

    @Test
    void absentBlockBindsNullAndIsNotLoaded() {
        assertFalse(new RivalryProperties(null, null, null, null).loaded());
    }

    @Test
    void aPartialBlockIsNotLoaded() {
        assertFalse(new RivalryProperties(3, 1.5, 2.5, null).loaded());
        assertFalse(new RivalryProperties(3, 1.5, null, 16).loaded());
        assertFalse(new RivalryProperties(3, null, 2.5, 16).loaded());
        assertFalse(new RivalryProperties(null, 1.5, 2.5, 16).loaded());
    }

    @Test
    void theHandSetStartingValuesLoad() {
        RivalryProperties p = new RivalryProperties(3, 1.5, 2.5, 16);
        assertTrue(p.loaded());
        assertEquals(3, p.minMeetings());
        assertEquals(16, p.maxManagers());
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(0, 1.5, 2.5, 16));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(11, 1.5, 2.5, 16));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, -0.1, 2.5, 16));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 10.5, 16));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 2.5, 1));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 2.5, 21));
        assertDoesNotThrow(() -> new RivalryProperties(1, 0.0, 0.0, 2));
        assertDoesNotThrow(() -> new RivalryProperties(10, 10.0, 10.0, 20));
    }
}
