package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiSecurityPropertiesTest {

    private static final ApiSecurityProperties ON = new ApiSecurityProperties("s3cret-token");
    private static final ApiSecurityProperties OFF = new ApiSecurityProperties("");

    @Test
    void blankTokenDisablesAuthEntirely() {
        assertFalse(OFF.enabled());
        assertFalse(new ApiSecurityProperties(null).enabled());
        assertFalse(new ApiSecurityProperties("   ").enabled());
    }

    @Test
    void aDisabledGateMatchesNothing() {
        // enabled() is what exempts the request; matches() must never wave one through
        assertFalse(OFF.matches(""));
        assertFalse(OFF.matches("anything"));
        assertFalse(OFF.matches(null));
    }

    @Test
    void matchesOnlyTheExactToken() {
        assertTrue(ON.matches("s3cret-token"));
        assertFalse(ON.matches("s3cret-toke"));
        assertFalse(ON.matches("s3cret-token "));
        assertFalse(ON.matches("S3CRET-TOKEN"));
        assertFalse(ON.matches(""));
        assertFalse(ON.matches(null));
    }

    @Test
    void bearerParsingIsCaseInsensitiveOnTheSchemeOnly() {
        assertEquals("abc", ApiSecurityProperties.bearer("Bearer abc"));
        assertEquals("abc", ApiSecurityProperties.bearer("bearer abc"));
        assertEquals("abc", ApiSecurityProperties.bearer("BEARER abc"));
        assertEquals("aBc", ApiSecurityProperties.bearer("Bearer aBc"));
    }

    @Test
    void malformedAuthorizationHeadersYieldNull() {
        assertNull(ApiSecurityProperties.bearer(null));
        assertNull(ApiSecurityProperties.bearer(""));
        assertNull(ApiSecurityProperties.bearer("Bearer"));
        assertNull(ApiSecurityProperties.bearer("Bearer "));
        assertNull(ApiSecurityProperties.bearer("Bearer    "));
        assertNull(ApiSecurityProperties.bearer("Basic abc"));
        assertNull(ApiSecurityProperties.bearer("abc"));
    }

    @Test
    void surroundingWhitespaceOnTheValueIsTrimmed() {
        assertEquals("abc", ApiSecurityProperties.bearer("Bearer   abc  "));
    }
}
