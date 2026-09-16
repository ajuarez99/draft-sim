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
     * Every position a fantasy roster can hold. Sent explicitly because the
     * endpoint filters on it; omitting the array does not mean "all".
     */
    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE", "K", "DEF");

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
                    POSITIONS.forEach(p -> b.queryParam("position[]", p));
                    return b.build(sport, season, week);
                })
                .retrieve()
                .body(List.class);
    }
}
