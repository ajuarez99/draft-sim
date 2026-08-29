package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A single shared bearer token. Deliberately not Spring Security: there is one
 * user, there is no user model, and a filter plus a constant-time compare is the
 * whole requirement. If this ever gets a second user, replace it rather than
 * growing it.
 *
 * A blank token disables the check entirely, which is the local-dev default.
 */
@ConfigurationProperties(prefix = "draftsim.security")
public record ApiSecurityProperties(String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }

    /**
     * Constant-time comparison. A timing side-channel on a shared secret is a
     * real thing and avoiding it costs nothing.
     */
    public boolean matches(String presented) {
        if (!enabled() || presented == null) return false;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Pulls the token out of "Authorization: Bearer xyz". Null if absent or malformed. */
    public static String bearer(String authorizationHeader) {
        if (authorizationHeader == null) return null;
        String prefix = "Bearer ";
        if (authorizationHeader.length() <= prefix.length()) return null;
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String value = authorizationHeader.substring(prefix.length()).trim();
        return value.isEmpty() ? null : value;
    }
}
