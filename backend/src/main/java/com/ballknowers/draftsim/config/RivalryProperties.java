package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rivalry-week settings (spec 034), bound from {@code draftsim.rivalry} in config/weights.yml.
 * ARBITRARY: every value is hand-set, not fitted (research R5/R6).
 *
 * <ul>
 *   <li>{@code minMeetings} (N): a pairing with fewer scored meetings than this is labelled thin.
 *   <li>{@code playoffBonus}: score added per winners-bracket elimination meeting (or final).
 *   <li>{@code championshipBonus}: score added once when the pair met in a final.
 *   <li>{@code maxManagers}: the exact matching is a bitmask DP, so it is capped; a larger league is
 *       refused with a reason rather than approximated.
 * </ul>
 *
 * <p>A <b>missing</b> block binds to null fields and startup still succeeds (weights.yml is an optional
 * import); there is deliberately no silent default. A <b>partial</b> block is just as unloaded
 * (plan.md F17): {@link #loaded()} needs every field. A value that is present is range-checked:
 * {@code minMeetings} 1-10, bonuses 0-10, {@code maxManagers} 2-20.
 */
@ConfigurationProperties(prefix = "draftsim.rivalry")
public record RivalryProperties(Integer minMeetings, Double playoffBonus, Double championshipBonus,
                                Integer maxManagers) {

    public RivalryProperties {
        if (minMeetings != null && (minMeetings < 1 || minMeetings > 10)) {
            throw new IllegalArgumentException(
                    "draftsim.rivalry.min-meetings must satisfy 1 <= n <= 10, got " + minMeetings);
        }
        if (playoffBonus != null && (playoffBonus < 0 || playoffBonus > 10)) {
            throw new IllegalArgumentException(
                    "draftsim.rivalry.playoff-bonus must satisfy 0 <= b <= 10, got " + playoffBonus);
        }
        if (championshipBonus != null && (championshipBonus < 0 || championshipBonus > 10)) {
            throw new IllegalArgumentException(
                    "draftsim.rivalry.championship-bonus must satisfy 0 <= b <= 10, got " + championshipBonus);
        }
        if (maxManagers != null && (maxManagers < 2 || maxManagers > 20)) {
            throw new IllegalArgumentException(
                    "draftsim.rivalry.max-managers must satisfy 2 <= n <= 20, got " + maxManagers);
        }
    }

    /** True only when every field is set; a partial block is not loaded (F17). */
    public boolean loaded() {
        return minMeetings != null && playoffBonus != null && championshipBonus != null && maxManagers != null;
    }
}
