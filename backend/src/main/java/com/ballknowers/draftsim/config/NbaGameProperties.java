package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * NBA game exclusions (spec 022, Cup-final decision 2026-10-08), bound from {@code draftsim.nba-games}
 * in config/weights.yml.
 *
 * <p>{@code excludedGameIds} are Sleeper game ids dropped from every real-basketball figure: Sleeper
 * stores NBA Cup championship games as regular-season games with real team codes, which the NBA does
 * not count in regular-season stats. The list is hand-maintained (one id per season), not fitted.
 *
 * <p>A missing or null list binds to an empty list. That is deliberate and unlike the other property
 * records: "exclude nothing" is a legitimate value here, not a rule that must be asserted, so there is
 * no NOT_CONFIGURED state to report.
 */
@ConfigurationProperties(prefix = "draftsim.nba-games")
public record NbaGameProperties(List<String> excludedGameIds) {

    public NbaGameProperties {
        excludedGameIds = excludedGameIds == null ? List.of() : List.copyOf(excludedGameIds);
    }

    /** The ids as a set, for {@code NbaGameLines.of}. */
    public Set<String> excludedGameIdSet() {
        return Set.copyOf(excludedGameIds);
    }

    public static NbaGameProperties none() {
        return new NbaGameProperties(List.of());
    }
}
