package com.ballknowers.draftsim.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Sleeper's weekly projections (claude/league-analysis.md).
 *
 * Deliberately not a method on {@link SleeperClient}. That client is pinned to
 * {@code /v1}; this endpoint lives outside it, is absent from Sleeper's public
 * docs, and carries no version in its path at all. Folding it in would lend it
 * the stability the documented surface has and hide, at the call site, that
 * this is the one thing here that can vanish without a deprecation.
 *
 * Measured 2026-09-15 against nfl/2026 weeks 2, 8, 14, 15 and 18: 200 with no
 * auth, ~3,300 rows and ~2.1 MB per week at 270–320 ms, of which 445–511 carry
 * a real points projection; the rest are stubs holding only an ADP. Attributed
 * to {@code company: "rotowire"}.
 */
@Component
public class SleeperProjectionClient {

    /**
     * Every position a fantasy roster can hold, per sport. Sent explicitly because the
     * endpoint filters on it; omitting the array does not mean "all". Football's list is
     * unchanged from before spec 030; basketball's is the five positions Sleeper tags NBA
     * players with (the same five {@code Position.forSport(NBA)} holds).
     */
    private static final List<String> NFL_POSITIONS = List.of("QB", "RB", "WR", "TE", "K", "DEF");
    private static final List<String> NBA_POSITIONS = List.of("PG", "SG", "SF", "PF", "C");

    static List<String> positionsFor(String sport) {
        return "nba".equals(sport) ? NBA_POSITIONS : NFL_POSITIONS;
    }

    private final RestClient http;

    public SleeperProjectionClient(
            @Value("${sleeper.projections-base-url:https://api.sleeper.app}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * One week of projections, raw. Rows without a points projection are
     * included as Sleeper sends them -- filtering is the ingest service's job,
     * so that "how many of these were real" stays a measurable number rather
     * than something this client silently decides.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> week(String sport, int season, int week) {
        return http.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/projections/{sport}/{season}/{week}")
                            .queryParam("season_type", "regular")
                            .queryParam("order_by", "ppr");
                    positionsFor(sport).forEach(p -> b.queryParam("position[]", p));
                    return b.build(sport, season, week);
                })
                .retrieve()
                .body(List.class);
    }

    /**
     * One whole season of projections, raw (specs/030-post-draft-season-view research R1).
     * Measured 2026-10-11 against nba/2026: 200, ~1.5 MB, 0.37 s, 2,120 rows of which 529
     * carry a stat line; every stat is a per-game average ({@code stats.gp} is 1.0). Filtering
     * is the ingest service's job, as with {@link #week}.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> season(String sport, int season) {
        return http.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/projections/{sport}/{season}")
                            .queryParam("season_type", "regular");
                    positionsFor(sport).forEach(p -> b.queryParam("position[]", p));
                    return b.build(sport, season);
                })
                .retrieve()
                .body(List.class);
    }
}
