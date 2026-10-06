package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** specs/018-draft-grades T005. */
class DraftGradePropertiesTest {

    @Test
    void absentBlockBindsNullAndIsNotLoaded() {
        DraftGradeProperties p = new DraftGradeProperties(null);
        assertNull(p.minPicksPerPosition());
        assertFalse(p.loaded());
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DraftGradeProperties(0));
        assertThrows(IllegalArgumentException.class, () -> new DraftGradeProperties(2));
        assertThrows(IllegalArgumentException.class, () -> new DraftGradeProperties(31));
        assertThrows(IllegalArgumentException.class, () -> new DraftGradeProperties(-1));
    }

    @Test
    void validValuesLoad() {
        assertEquals(8, new DraftGradeProperties(8).minPicksPerPosition());
        assertTrue(new DraftGradeProperties(8).loaded());
        assertTrue(new DraftGradeProperties(3).loaded());
        assertTrue(new DraftGradeProperties(30).loaded());
    }
}
