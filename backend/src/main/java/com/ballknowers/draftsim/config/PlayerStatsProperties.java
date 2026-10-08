package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Player stat analysis settings (spec 022), bound from {@code draftsim.player-stats} in
 * config/weights.yml. Every value is ARBITRARY: hand-set, not fitted (research R12).
 *
 * <ul>
 *   <li>{@code rankMinGamesShare}: share of the season's team games a player must have played to be ranked</li>
 *   <li>{@code rankMinMinutesPerGame}: minutes-per-game floor for ranking</li>
 *   <li>{@code smallSampleMinutes}: total minutes below which a rate stat is flagged a small sample</li>
 *   <li>{@code recencyDays}: a player whose last game is more than this many days before the season's
 *       latest game is stale</li>
 *   <li>{@code standoutMinPriorGames}: prior games needed before a game can stand out</li>
 *   <li>{@code standoutTsDelta}: true-shooting points above or below the player's season TS% before that night</li>
 *   <li>{@code standoutTsMinAttempts}: true-shooting attempts (FGA + 0.44 FTA) a game needs for its TS% to count</li>
 *   <li>{@code standoutMinutesJump}: minutes above the player's last-5 mean before that night, free agents only</li>
 *   <li>{@code leadersSize}: rows per stat-leaders category</li>
 * </ul>
 *
 * <p>A <b>missing</b> block binds to null fields and startup still succeeds (weights.yml is an optional
 * import); there is deliberately no silent default. A value that is <b>present</b> must be positive
 * ({@code rankMinGamesShare} in (0, 1]) and a bad one fails startup.
 */
@ConfigurationProperties(prefix = "draftsim.player-stats")
public record PlayerStatsProperties(Double rankMinGamesShare, Integer rankMinMinutesPerGame,
                                    Integer smallSampleMinutes, Integer recencyDays,
                                    Integer standoutMinPriorGames, Integer standoutTsDelta,
                                    Integer standoutTsMinAttempts, Integer standoutMinutesJump,
                                    Integer leadersSize) {

    public PlayerStatsProperties {
        if (rankMinGamesShare != null && !(rankMinGamesShare > 0.0 && rankMinGamesShare <= 1.0)) {
            throw new IllegalArgumentException(
                    "draftsim.player-stats.rank-min-games-share must satisfy 0 < s <= 1, got " + rankMinGamesShare);
        }
        positive("rank-min-minutes-per-game", rankMinMinutesPerGame);
        positive("small-sample-minutes", smallSampleMinutes);
        positive("recency-days", recencyDays);
        positive("standout-min-prior-games", standoutMinPriorGames);
        positive("standout-ts-delta", standoutTsDelta);
        positive("standout-ts-min-attempts", standoutTsMinAttempts);
        positive("standout-minutes-jump", standoutMinutesJump);
        positive("leaders-size", leadersSize);
    }

    private static void positive(String name, Integer v) {
        if (v != null && v <= 0) {
            throw new IllegalArgumentException("draftsim.player-stats." + name + " must be positive, got " + v);
        }
    }

    /** All nine values present. */
    public boolean loaded() {
        return rankMinGamesShare != null && rankMinMinutesPerGame != null && smallSampleMinutes != null
                && recencyDays != null && standoutMinPriorGames != null && standoutTsDelta != null
                && standoutTsMinAttempts != null && standoutMinutesJump != null && leadersSize != null;
    }
}
