package com.ballknowers.draftsim.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one constant-time secret comparison. The bearer token
 * ({@link ApiSecurityProperties}), the daily-refresh secret
 * ({@code RefreshProperties}) and the admin token ({@link AdminProperties}) all
 * used to want their own copy of this; a timing side-channel on a shared secret
 * is real and avoiding it costs nothing, so it lives here once.
 *
 * <p>Fails closed: a blank configured secret matches nothing, including a blank
 * presented value.
 */
public final class SecretCompare {

    private SecretCompare() {}

    public static boolean matches(String configured, String presented) {
        if (configured == null || configured.isBlank() || presented == null) return false;
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
