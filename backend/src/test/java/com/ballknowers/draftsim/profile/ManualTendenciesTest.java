package com.ballknowers.draftsim.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManualTendenciesTest {

    @Test
    void valuesAreClampedToWhatTheEngineCanSurvive() {
        assertEquals(40.0, new ManualTendencies(999.0, null, null).reachBias());
        assertEquals(-40.0, new ManualTendencies(-999.0, null, null).reachBias());
        assertEquals(5.0, new ManualTendencies(null, 99.0, null).unpredictability());
        // Zero would make the softmax degenerate, so it floors rather than passing through.
        assertEquals(0.1, new ManualTendencies(null, 0.0, null).unpredictability());
    }

    @Test
    void nullMeansNoOpinionAndSurvivesAsNull() {
        ManualTendencies t = new ManualTendencies(null, null, null);
        assertNull(t.reachBias());
        assertNull(t.unpredictability());
        assertNull(t.note());
        assertTrue(t.isEmpty());
    }

    @Test
    void notesAreTrimmedBoundedAndBlankBecomesNull() {
        assertNull(new ManualTendencies(null, null, "   ").note());
        assertEquals("hi", new ManualTendencies(null, null, "  hi  ").note());
        assertEquals(280, new ManualTendencies(null, null, "x".repeat(400)).note().length());
    }

    /**
     * A note is an annotation, not a behaviour change. Provenance depends on this
     * distinction: a seat with only a note is still the league-average drafter and
     * must not be reported as one the user has configured.
     */
    @Test
    void aNoteAloneDoesNotChangeHowTheSeatDrafts() {
        assertFalse(new ManualTendencies(null, null, "drafts his own Bengals").affectsBehaviour());
        assertTrue(new ManualTendencies(5.0, null, null).affectsBehaviour());
        assertTrue(new ManualTendencies(null, 2.0, null).affectsBehaviour());
    }
}
