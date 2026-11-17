package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 074 T004: who the caller is. The absent input next to the wrong one (repo rule for a gate). */
class IdentityTest {

    private static final String UUID1 = "123e4567-e89b-12d3-a456-426614174000";

    private static HttpStatus statusOf(String sleeper, String guest) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> Identity.resolve(sleeper, guest));
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void aSleeperIdWinsOverAGuestId() {
        assertEquals("alice", Identity.resolve("alice", UUID1));
        assertEquals("alice", Identity.resolve("alice", null));
        assertEquals("alice", Identity.resolve("alice", "not-a-uuid"), "the Sleeper id decides; the guest header is never read");
    }

    @Test
    void aWellFormedGuestIdBecomesTheLowercaseOwnerKey() {
        assertEquals("guest:" + UUID1, Identity.resolve(null, UUID1));
        assertEquals("guest:" + UUID1, Identity.resolve("", UUID1), "a blank Sleeper header counts as absent");
        assertEquals("guest:" + UUID1, Identity.resolve("   ", UUID1));
        assertEquals("guest:" + UUID1, Identity.resolve(null, UUID1.toUpperCase()), "one owner key per uuid, whatever its case");
        assertEquals("guest:" + UUID1, Identity.resolve(null, "  " + UUID1 + " "));
    }

    @Test
    void noIdentityAtAllAndAMalformedGuestIdAreTheSame401() {
        for (String bad : new String[]{null, "", "  ", "abc", UUID1 + "x", "guest:" + UUID1, "123e4567e89b12d3a456426614174000",
                "123e4567-e89b-12d3-a456-42661417400g", "' or 1=1 --"}) {
            assertEquals(HttpStatus.UNAUTHORIZED, statusOf(null, bad), "guest id " + bad);
        }
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("", ""));
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("  ", null));
    }

    @Test
    void theNilUuidIsNotAnIdentity() {
        // CR-11: a placeholder every buggy client would share. Same refusal as a missing or malformed one.
        for (String nil : new String[]{"00000000-0000-0000-0000-000000000000", " 00000000-0000-0000-0000-000000000000 "}) {
            assertEquals(HttpStatus.UNAUTHORIZED, statusOf(null, nil), "guest id " + nil);
            assertNull(Identity.guestKey(nil));
        }
    }

    @Test
    void shortHashIsStableAndNeverTheKey() {
        String k = "guest:" + UUID1;
        assertEquals(Identity.shortHash(k), Identity.shortHash(k));
        assertEquals(8, Identity.shortHash(k).length());
        assertFalse(Identity.shortHash(k).contains(UUID1.substring(0, 8)));
    }

    @Test
    void aSleeperIdThatLooksLikeAGuestKeyIsRefusedSoTheNamespacesCannotCollide() {
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("guest:" + UUID1, null));
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("guest:" + UUID1, UUID1), "even with a valid guest id beside it");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("GUEST:anything", null), "any case");
        assertEquals(HttpStatus.BAD_REQUEST, statusOf("guest:", null));
        assertEquals("guestlike", Identity.resolve("guestlike", null), "only the prefix with its colon is reserved");
    }

    @Test
    void sleeperIdAndGuestKeyHelpersUsedByClaim() {
        assertEquals("alice", Identity.sleeperId("alice"));
        assertEquals(HttpStatus.UNAUTHORIZED, HttpStatus.valueOf(assertThrows(ResponseStatusException.class,
                () -> Identity.sleeperId(null)).getStatusCode().value()));
        assertEquals(HttpStatus.UNAUTHORIZED, HttpStatus.valueOf(assertThrows(ResponseStatusException.class,
                () -> Identity.sleeperId("  ")).getStatusCode().value()));
        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(assertThrows(ResponseStatusException.class,
                () -> Identity.sleeperId("guest:" + UUID1)).getStatusCode().value()));
        assertNull(Identity.guestKey(null));
        assertNull(Identity.guestKey(""));
        assertNull(Identity.guestKey("  "));
        assertNull(Identity.guestKey("nope"));
        assertEquals("guest:" + UUID1, Identity.guestKey(UUID1));
    }

    @Test
    void isGuestReadsTheOwnerKey() {
        assertTrue(Identity.isGuest("guest:" + UUID1));
        assertFalse(Identity.isGuest("alice"));
        assertFalse(Identity.isGuest(""));
        assertFalse(Identity.isGuest(null));
    }
}
