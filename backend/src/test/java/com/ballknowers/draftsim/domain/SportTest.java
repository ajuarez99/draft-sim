package com.ballknowers.draftsim.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins Sport's JSON wire form to the lowercase code -- the same shape the
 * {@code ?sport=} query params and the {@code league.sport} column already use
 * (multi-sport-and-rebrand.md Phase 2). Before this, Jackson's default enum
 * handling serialized by {@code name()}, so a fresh field like
 * DraftRepository.DraftSummary.sport would have emitted {@code "NFL"} while
 * every other sport-shaped value on the wire spelled it {@code "nfl"}. Guards
 * against that drifting back silently.
 */
class SportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesAsTheLowercaseCodeNotTheEnumName() throws Exception {
        assertEquals("\"nfl\"", mapper.writeValueAsString(Sport.NFL));
        assertEquals("\"nba\"", mapper.writeValueAsString(Sport.NBA));
    }

    @Test
    void deserializesFromTheLowercaseCode() throws Exception {
        assertEquals(Sport.NFL, mapper.readValue("\"nfl\"", Sport.class));
        assertEquals(Sport.NBA, mapper.readValue("\"nba\"", Sport.class));
    }

    @Test
    void deserializingAnUppercaseEnumNameFails() {
        // The old wire shape must not silently keep working -- a caller sending
        // "NFL" (Sport::name) should fail loudly, not be quietly accepted
        // alongside "nfl", which would leave two representations live again.
        assertThrows(Exception.class, () -> mapper.readValue("\"NFL\"", Sport.class));
    }

    @Test
    void fromCodeStillThrowsIllegalArgumentOnAnUnknownCode() {
        // Unchanged non-Jackson behaviour: the ?sport= query param binding relies
        // on this to turn a bad value into a 400, not a 500.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Sport.fromCode("mlb"));
        assertTrue(ex.getMessage().contains("mlb"), ex.getMessage());
    }
}
