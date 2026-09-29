package com.ballknowers.draftsim.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sleeper's unversioned stats and schedule endpoints
 * (specs/005-daily-weekly-top-players, US1; rebuilt for
 * specs/009-auto-data-refresh, research R6).
 *
 * <p>Deliberately not a method on {@link SleeperClient}, for exactly the reason
 * {@link SleeperProjectionClient} gives about its own endpoint: that client is
 * pinned to {@code /v1}, and these endpoints live outside it, carry no version
 * in their path, and are absent from Sleeper's public docs. Folding them in would
 * lend them the stability the documented surface has and hide, at the call site,
 * that they can vanish without a deprecation.
 *
 * <p>The per-player season endpoint ({@code /stats/{sport}/player/{id}}) this
 * client used to wrap was removed: it cost one call per player per league and
 * could not be made incremental. The per-week endpoint returns every player's
 * entries for a week in one call.
 */
@Component
public class SleeperPlayerStatsClient {

    private final RestClient http;

    public SleeperPlayerStatsClient(
            @Value("${sleeper.stats-base-url:https://api.sleeper.app}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Every player's per-game entries for one week of one season, from the
     * per-week endpoint (specs/009-auto-data-refresh, research R6, measured
     * 2026-09-28): {@code GET /stats/{sport}/{season}/{week}?season_type=regular}.
     *
     * <p>A JSON array of entries with the same fields as the per-player
     * endpoint ({@code player_id}, {@code game_id}, {@code date}, {@code team},
     * {@code opponent}, {@code week}, {@code stats}) except {@code is_away_team},
     * which only the per-player endpoint carries -- {@code is_away} comes from
     * {@link #schedule}. Empty-stats entries (a DNP) are included.
     *
     * <p>Defensively typed: the body is read as an untyped {@code Object} and
     * only elements that really are maps are kept, never trusting a declared
     * generic type (see the note in {@code PlayerGameIngestService}).
     *
     * @param sport Sleeper's own path segment, not this app's enum
     */
    public List<Map<String, Object>> week(String sport, int season, int week) {
        Object body = http.get()
                .uri(b -> b.path("/stats/{sport}/{season}/{week}")
                        .queryParam("season_type", "regular")
                        .build(sport, season, week))
                .retrieve()
                .body(Object.class);
        return mapsOf(body);
    }

    /**
     * A season's regular-season schedule: {@code GET /schedule/{sport}/regular/{season}}.
     * One array of games carrying {@code game_id}, {@code week}, {@code date},
     * {@code status} and home/away. The home/away value's shape differs by sport
     * (a nested object with {@code team}, or a bare team code); resolving that is
     * the caller's job, not this client's -- it returns the raw maps.
     */
    public List<Map<String, Object>> schedule(String sport, int season) {
        Object body = http.get()
                .uri(b -> b.path("/schedule/{sport}/regular/{season}").build(sport, season))
                .retrieve()
                .body(Object.class);
        return mapsOf(body);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapsOf(Object body) {
        if (!(body instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }
}
