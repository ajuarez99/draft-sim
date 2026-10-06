package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Minutes-trend and streaming settings (spec 019), bound from {@code draftsim.player-trends} in
 * config/weights.yml. Every value is ARBITRARY: hand-set, not fitted (research R5, review F7).
 *
 * <ul>
 *   <li>{@code roleThresholdMinutes}: recent minus season minutes at or beyond which a player is a
 *       RISER (+) or FALLER (-)</li>
 *   <li>{@code minSeasonGames}: games before a season minutes mean is shown</li>
 *   <li>{@code formGames} / {@code formMinGames}: the window for "last N" points, and the games needed</li>
 *   <li>{@code recencyDays}: a player whose last game is more than this many days before the season's
 *       latest game is stale and excluded</li>
 *   <li>{@code listSize} / {@code streamingSize}: rows per risers/fallers list, and in streaming</li>
 *   <li>{@code oneGameShare}: the share of multi-game starter-weeks credited as exactly one game at or
 *       above which the league is described as crediting one game a week</li>
 * </ul>
 *
 * <p>A <b>missing</b> block binds to null fields and startup still succeeds (weights.yml is an optional
 * import); there is deliberately no silent default, and the endpoint answers NOT_CONFIGURED. A value
 * that is <b>present</b> must be positive ({@code oneGameShare} in (0, 1]) and a bad one fails startup.
 */
@ConfigurationProperties(prefix = "draftsim.player-trends")
public record PlayerTrendsProperties(Integer roleThresholdMinutes, Integer minSeasonGames, Integer formGames,
                                     Integer formMinGames, Integer recencyDays, Integer listSize,
                                     Integer streamingSize, Double oneGameShare) {

    public PlayerTrendsProperties {
        positive("role-threshold-minutes", roleThresholdMinutes);
        positive("min-season-games", minSeasonGames);
        positive("form-games", formGames);
        positive("form-min-games", formMinGames);
        positive("recency-days", recencyDays);
        positive("list-size", listSize);
        positive("streaming-size", streamingSize);
        if (oneGameShare != null && !(oneGameShare > 0.0 && oneGameShare <= 1.0)) {
            throw new IllegalArgumentException(
                    "draftsim.player-trends.one-game-share must satisfy 0 < s <= 1, got " + oneGameShare);
        }
    }

    private static void positive(String name, Integer v) {
        if (v != null && v <= 0) {
            throw new IllegalArgumentException("draftsim.player-trends." + name + " must be positive, got " + v);
        }
    }

    /** All eight values present. */
    public boolean loaded() {
        return roleThresholdMinutes != null && minSeasonGames != null && formGames != null
                && formMinGames != null && recencyDays != null && listSize != null
                && streamingSize != null && oneGameShare != null;
    }
}
