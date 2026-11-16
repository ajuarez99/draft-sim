package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spec 073 follow-up (2026-11-16, second pass): the league API lock.
 *
 * @param enabled           OFF by default. With it off every route behaves exactly as it did before the lock existed,
 *                          so the live combined site (which shares this backend) is unaffected until the deploy-day
 *                          flip.
 * @param idleSeconds       how long a session token lives after it was issued or last renewed. ARBITRARY: hand-set,
 *                          Allan asked for "5 min or something". Not tuned against anything.
 * @param maxSessionSeconds the ABSOLUTE lifetime of a session, measured from the original unlock no matter how often
 *                          it was renewed (lock fix pass, CR-02). ARBITRARY: hand-set to 12 hours, a long working
 *                          day, so a leaked or scripted token cannot be kept alive forever by calling any route once
 *                          per idle window. Not tuned against anything.
 */
@ConfigurationProperties(prefix = "draftsim.league-lock")
public record LeagueLockProperties(boolean enabled, Integer idleSeconds, Integer maxSessionSeconds) {

    public static final int DEFAULT_IDLE_SECONDS = 300;
    public static final int DEFAULT_MAX_SESSION_SECONDS = 43_200;

    public LeagueLockProperties {
        if (idleSeconds == null) idleSeconds = DEFAULT_IDLE_SECONDS;
        if (maxSessionSeconds == null) maxSessionSeconds = DEFAULT_MAX_SESSION_SECONDS;
        if (idleSeconds <= 0) {
            throw new IllegalStateException("draftsim.league-lock.idle-seconds must be positive, got " + idleSeconds);
        }
        if (maxSessionSeconds <= 0) {
            throw new IllegalStateException(
                    "draftsim.league-lock.max-session-seconds must be positive, got " + maxSessionSeconds);
        }
    }
}
