package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.TestAdmin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The secret comparison and the "is this request admin?" question, without Spring.
 * The polarity to pin: a BLANK configured token disables admin (matches nothing), the
 * opposite of ApiSecurityProperties, where blank turns the bearer gate off.
 */
class AdminAccessTest {

    private static final AdminProperties ON = new AdminProperties("s3cret-admin");
    private static final AdminProperties OFF = new AdminProperties("");

    @Test
    void aBlankOrMissingConfiguredTokenDisablesAdminAndMatchesNothing() {
        for (AdminProperties p : new AdminProperties[] {OFF, new AdminProperties(null), new AdminProperties("   ")}) {
            assertFalse(p.enabled());
            assertFalse(p.matches(""), "a blank presented value must not match a blank configured one");
            assertFalse(p.matches(null));
            assertFalse(p.matches("anything"));
            assertFalse(p.matches("   "));
        }
    }

    @Test
    void onlyTheExactTokenMatches() {
        assertTrue(ON.enabled());
        assertTrue(ON.matches("s3cret-admin"));
        assertFalse(ON.matches("s3cret-admi"));
        assertFalse(ON.matches("s3cret-admin "));
        assertFalse(ON.matches("S3CRET-ADMIN"));
        assertFalse(ON.matches(""));
        assertFalse(ON.matches(null));
    }

    @Test
    void theSharedCompareBehavesTheSameForEveryCaller() {
        // The bearer token and the refresh secret went through their own copies of this; one helper now.
        assertTrue(SecretCompare.matches("a", "a"));
        assertFalse(SecretCompare.matches("a", "b"));
        assertFalse(SecretCompare.matches("", ""));
        assertFalse(SecretCompare.matches(null, null));
        assertFalse(new ApiSecurityProperties("").matches(""));
        assertTrue(new ApiSecurityProperties("tok").matches("tok"));
    }

    @Test
    void isAdminReadsTheCurrentRequestsHeaderAndNothingElse() {
        AdminAccess access = new AdminAccess(ON);

        assertFalse(access.isAdmin(), "no request bound to this thread: nobody is admin");
        try (var r = TestAdmin.withToken("s3cret-admin")) {
            assertTrue(access.isAdmin());
        }
        try (var r = TestAdmin.withToken("wrong")) {
            assertFalse(access.isAdmin());
        }
        try (var r = TestAdmin.withToken("")) {
            assertFalse(access.isAdmin());
        }
        try (var r = TestAdmin.withToken(null)) {
            assertFalse(access.isAdmin());
        }
        assertFalse(access.isAdmin(), "and the binding is gone once the scope closes");
    }

    @Test
    void aDisabledAdminIsNeverAdminEvenWhenARequestPresentsSomething() {
        AdminAccess access = new AdminAccess(OFF);
        for (String presented : new String[] {"", "x", "s3cret-admin"}) {
            try (var r = TestAdmin.withToken(presented)) {
                assertFalse(access.isAdmin());
            }
        }
    }
}
