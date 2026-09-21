package com.ballknowers.draftsim.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * One player's season, game by game (specs/005-daily-weekly-top-players, US1).
 *
 * <p>Deliberately not a method on {@link SleeperClient}, for exactly the reason
 * {@link SleeperProjectionClient} gives about its own endpoint: that client is
 * pinned to {@code /v1}, and this endpoint lives outside it, carries no version
 * in its path, and is absent from Sleeper's public docs. Folding it in would
 * lend it the stability the documented surface has and hide, at the call site,
 * that this can vanish without a deprecation.
 *
 * <p>Measured 2026-09-19 against nba player 1658: 200 with no auth, ~83 KB for
 * season 2024 and ~92 KB for 2025, in ~300 ms. The response is an object keyed
 * by fantasy week ({@code "1"}..{@code "25"}), each value a <b>list of per-game
 * entries</b> carrying {@code date}, {@code opponent}, {@code is_away_team},
 * {@code game_id}, {@code week} and a full {@code stats} box score.
 *
 * <p>This shape is why the feature is affordable: one call returns a player's
 * whole season, so a league-season backfill costs one call per player (~331 for
 * the reference league) rather than one per player per week. Two alternatives
 * were measured and rejected -- the bulk weekly endpoint carries no {@code date}
 * at all, and {@code ?date=} on the season endpoint is ignored, returning
 * byte-identical season totals for different dates.
 */
@Component
public class SleeperPlayerStatsClient {

    private final RestClient http;

    public SleeperPlayerStatsClient(
            @Value("${sleeper.stats-base-url:https://api.sleeper.app}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Every game one player played in a season, keyed by fantasy week.
     *
     * <p>Returned raw. Which entries are usable is the ingest service's job, so
     * that "how many games did this player actually have" stays a measurable
     * number rather than something this client silently decides.
     *
     * @param sport Sleeper's own path segment ({@code "nba"}), not this app's enum
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> seasonByWeek(String sport, String playerId, int season) {
        return http.get()
                .uri(b -> b.path("/stats/{sport}/player/{playerId}")
                        .queryParam("season_type", "regular")
                        .queryParam("season", season)
                        .queryParam("grouping", "week")
                        .build(sport, playerId))
                .retrieve()
                .body(Map.class);
    }
}
