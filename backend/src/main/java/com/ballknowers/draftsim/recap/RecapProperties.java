package com.ballknowers.draftsim.recap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code draftsim.recap.*} (specs/020-ai-weekly-recap). Every number is hand-set and ARBITRARY
 * (research R2/R4, review F1/N7), a bound on spend and waiting, not a measured optimum.
 *
 * <p><b>There is deliberately no key field.</b> The Anthropic key is read from the environment
 * only ({@link RecapEnabledCondition}, {@link RecapConfig}), so no record, {@code toString()} or
 * actuator dump of this class can ever print it (review F12, FR-008).
 *
 * @param enabled                 master switch; off by default
 * @param model                   Anthropic model id used for every league
 * @param maxCallsPerLeaguePerDay API calls per league per UTC day (a grounding retry counts as 2)
 * @param maxCallsPerDay          API calls across all leagues per UTC day
 * @param transientRetryMinutes   wait before retrying an upstream API error or rate limit
 * @param timeoutSeconds          per-request timeout
 * @param maxTokens               output token cap for a recap
 */
@ConfigurationProperties(prefix = "draftsim.recap")
public record RecapProperties(
        boolean enabled,
        String model,
        int maxCallsPerLeaguePerDay,
        int maxCallsPerDay,
        int transientRetryMinutes,
        int timeoutSeconds,
        int maxTokens) {

    public RecapProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("draftsim.recap.model must not be blank");
        }
        requirePositive("max-calls-per-league-per-day", maxCallsPerLeaguePerDay);
        requirePositive("max-calls-per-day", maxCallsPerDay);
        requirePositive("transient-retry-minutes", transientRetryMinutes);
        requirePositive("timeout-seconds", timeoutSeconds);
        requirePositive("max-tokens", maxTokens);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("draftsim.recap." + name + " must be positive, was " + value);
        }
    }
}
