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
 *   <li>{@code maxRegularSeasonMeetings} (spec 037): the most times any two managers may meet in the
 *       regular season once rivalry week is in. <b>A LEAGUE RULE (Allan, 2026-10-15), not fitted.</b>
 *       Range 1-5. It is deliberately NOT part of {@link #loaded()}: a missing cap must not blank spec
 *       034's pairings (plan F7), so the week pick has its own {@link #weekLoaded()}.
 * </ul>
 *
 * <p>A <b>missing</b> block binds to null fields and startup still succeeds (weights.yml is an optional
 * import); there is deliberately no silent default. A <b>partial</b> block is just as unloaded
 * (plan.md F17): {@link #loaded()} needs every field. A value that is present is range-checked:
 * {@code minMeetings} 1-10, bonuses 0-10, {@code maxManagers} 2-20.
 */
@ConfigurationProperties(prefix = "draftsim.rivalry")
public record RivalryProperties(Integer minMeetings, Double playoffBonus, Double championshipBonus,
                                Integer maxManagers, Integer maxRegularSeasonMeetings) {

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
        if (maxRegularSeasonMeetings != null && (maxRegularSeasonMeetings < 1 || maxRegularSeasonMeetings > 5)) {
            throw new IllegalArgumentException(
                    "draftsim.rivalry.max-regular-season-meetings must satisfy 1 <= n <= 5, got "
                            + maxRegularSeasonMeetings);
        }
    }

    /** True only when every field is set; a partial block is not loaded (F17). */
    public boolean loaded() {
        return minMeetings != null && playoffBonus != null && championshipBonus != null && maxManagers != null;
    }

    /**
     * Whether the week pick can run (spec 037, plan F7): the spec 034 block is loaded AND the cap is set.
     * {@link #loaded()} is unchanged on purpose, so a weights.yml without the cap still gets spec 034's pairings
     * and answers {@code CONFIG_MISSING} for the week alone.
     */
    public boolean weekLoaded() {
        return loaded() && maxRegularSeasonMeetings != null;
    }
}
