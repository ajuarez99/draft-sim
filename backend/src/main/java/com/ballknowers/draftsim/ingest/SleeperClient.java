package com.ballknowers.draftsim.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sleeper's read API is public and unauthenticated. It is rate limited around
 * 1000 calls/minute, which nothing here comes close to except the players dump
 * (~5MB) — that one is meant to be pulled once a day and cached.
 */
@Component
public class SleeperClient {

    private final RestClient http;

    public SleeperClient(@Value("${sleeper.base-url}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Map<String, Object> user(String usernameOrId) {
        return http.get().uri("/user/{u}", usernameOrId).retrieve().body(Map.class);
    }

    public List<Map<String, Object>> leagues(String userId, String sport, int season) {
        return http.get()
                .uri("/user/{u}/leagues/{sport}/{season}", userId, sport, season)
                .retrieve().body(List.class);
    }

    public Map<String, Object> league(String leagueId) {
        return http.get().uri("/league/{id}", leagueId).retrieve().body(Map.class);
    }

    public List<Map<String, Object>> leagueUsers(String leagueId) {
        return http.get().uri("/league/{id}/users", leagueId).retrieve().body(List.class);
    }

    public List<Map<String, Object>> drafts(String leagueId) {
        return http.get().uri("/league/{id}/drafts", leagueId).retrieve().body(List.class);
    }

    public Map<String, Object> draft(String draftId) {
        return http.get().uri("/draft/{id}", draftId).retrieve().body(Map.class);
    }

    public List<Map<String, Object>> draftPicks(String draftId) {
        return http.get().uri("/draft/{id}/picks", draftId).retrieve().body(List.class);
    }

    /** ~5MB. Cache it; do not call per request. */
    public Map<String, Map<String, Object>> allPlayers(String sport) {
        return http.get().uri("/players/{sport}", sport).retrieve().body(Map.class);
    }

    /**
     * Walk previous_league_id backwards to collect every season this group has
     * drafted together. Sleeper links seasons only in this direction.
     */
    public List<Map<String, Object>> leagueChain(String currentLeagueId) {
        List<Map<String, Object>> chain = new ArrayList<>();
        String id = currentLeagueId;
        while (id != null && !id.isBlank() && !"null".equals(id)) {
            Map<String, Object> l = league(id);
            if (l == null) break;
            chain.add(l);
            Object prev = l.get("previous_league_id");
            id = prev == null ? null : prev.toString();
        }
        return chain;
    }
}
