package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Spec 044 hand-set numbers (research R7, plan amendment item 9), bound from {@code draftsim.lock-in} in
 * config/weights.yml. Every value is ARBITRARY: set before the payoff table was read and never retuned to
 * make a result look decisive.
 *
 * <p>All eight are required, like {@link PlayerTrendsProperties}'s validation: a missing or misspelled key
 * would otherwise bind to null/0 and quietly turn "too few" into a figure. Startup fails instead.
 *
 * @param minLocks       provable locks a manager or player needs before a median is shown
 * @param minCell        locks a payoff cell needs before it shows a rate
 * @param minPriorGames  prior same-season games a stand-in baseline needs
 * @param marginBands    ascending lower edges (fractions above baseline) of the 2nd..last payoff band
 * @param gamesLeftCap   payoff columns are 1..cap, the last meaning "cap or more"
 * @param thresholdCap   games-left values the Lock/Wait thresholds are fitted for, the last meaning "or more"
 * @param liveTtlSeconds how long a live-calls result is cached
 * @param liveFailureTtlSeconds how long a failed roster read is cached (negative cache), so a Sleeper outage is
 *                       not one retry per request
 */
@ConfigurationProperties(prefix = "draftsim.lock-in")
public record LockInProperties(Integer minLocks, Integer minCell, Integer minPriorGames, List<Double> marginBands,
                               Integer gamesLeftCap, Integer thresholdCap, Integer liveTtlSeconds,
                               Integer liveFailureTtlSeconds) {

    public LockInProperties {
        positive("min-locks", minLocks);
        positive("min-cell", minCell);
        positive("min-prior-games", minPriorGames);
        positive("games-left-cap", gamesLeftCap);
        positive("threshold-cap", thresholdCap);
        positive("live-ttl-seconds", liveTtlSeconds);
        positive("live-failure-ttl-seconds", liveFailureTtlSeconds);
        if (marginBands == null || marginBands.isEmpty()) {
            throw new IllegalArgumentException("draftsim.lock-in.margin-bands must be a non-empty list");
        }
        for (int i = 1; i < marginBands.size(); i++) {
            if (!(marginBands.get(i) > marginBands.get(i - 1))) {
                throw new IllegalArgumentException(
                        "draftsim.lock-in.margin-bands must be strictly ascending, got " + marginBands);
            }
        }
        marginBands = List.copyOf(marginBands);
    }

    private static void positive(String name, Integer v) {
        if (v == null || v <= 0) {
            throw new IllegalArgumentException("draftsim.lock-in." + name + " must be a positive number, got " + v);
        }
    }
}
