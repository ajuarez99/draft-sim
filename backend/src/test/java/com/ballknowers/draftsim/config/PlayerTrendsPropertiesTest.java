package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** specs/019-minutes-streaming T008. */
class PlayerTrendsPropertiesTest {

    private static PlayerTrendsProperties valid() {
        return new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9);
    }

    @Test
    void absentBlockBindsNullAndIsNotLoaded() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(null, null, null, null, null, null, null, null);
        assertFalse(p.loaded());
        assertNull(p.listSize());
    }

    @Test
    void allEightValuesLoad() {
        assertTrue(valid().loaded());
    }

    @Test
    void aPartialBlockIsNotLoaded() {
        assertFalse(new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, null, 0.9).loaded());
        assertFalse(new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, null).loaded());
    }

    @Test
    void nonPositiveValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(0, 5, 5, 3, 14, 10, 20, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, -1, 5, 3, 14, 10, 20, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 0, 3, 14, 10, 20, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 0, 14, 10, 20, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 3, 0, 10, 20, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 3, 14, 0, 20, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 0, 0.9));
    }

    @Test
    void oneGameShareMustBeInHalfOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 1.01));
        assertThrows(IllegalArgumentException.class, () -> new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, -0.5));
        assertTrue(new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 1.0).loaded());
    }
}
