package com.ballknowers.draftsim.refresh;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration and named constants for automatic data refresh
 * (specs/009-auto-data-refresh).
 *
 * <p>The constants below are rules, not tunables: each is hand-set and
 * arbitrary, labelled as such with the research entry it came from, and none is
 * a defaulted parameter anywhere (AGENTS.md).
 *
 * @param onVisit refresh a league's stale seasons when its pages are opened;
 *                {@code refresh.on-visit.enabled}, default true. Tests turn it
 *                off (research R12)
 * @param secret  {@code refresh.secret}, from {@code REFRESH_SECRET}; blank means
 *                the daily route doesn't exist (research R8)
 */
@ConfigurationProperties(prefix = "refresh")
public record RefreshProperties(@DefaultValue OnVisit onVisit, String secret) {

    public record OnVisit(@DefaultValue("true") boolean enabled) {}

    public boolean onVisitEnabled() {
        return onVisit == null || onVisit.enabled();
    }

    public boolean dailyRouteConfigured() {
        return secret != null && !secret.isBlank();
    }

    /** Hand-set, arbitrary: an active league-season is stale after this long (FR-002, research R3). */
    public static final Duration STALE_AFTER = Duration.ofHours(1);

    /** Hand-set, arbitrary: how long to leave a failed league-season alone before retrying (refresh contract). */
    public static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    /**
     * Hand-set, arbitrary and not measured: a per-game week is final once fetched
     * at least this long after its last scheduled game, to cover Sleeper's stat
     * corrections (research R6).
     */
    public static final Duration WEEK_FINAL_AFTER = Duration.ofHours(48);

    /** Hand-set, arbitrary: at most this many league refreshes run at once (research R4). */
    public static final int MAX_CONCURRENT_REFRESHES = 2;
}
