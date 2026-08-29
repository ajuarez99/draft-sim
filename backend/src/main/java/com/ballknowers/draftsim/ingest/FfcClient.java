package com.ballknowers.draftsim.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Fantasy Football Calculator's free ADP API. Free for personal and commercial
 * use; they ask only for attribution and that it not be hammered since the
 * data refreshes once a day — see claude/adp-sources.md #10.
 *
 * Verified live this session: the response body is valid JSON, but FFC serves
 * it with {@code Content-Type: text/html; charset=utf-8} — confirmed with a
 * plain curl, so it's the server, not this client. Spring's default
 * content-negotiating converter refuses to parse JSON out of a body declared
 * text/html, so this fetches as a raw String and parses it directly rather
 * than trusting the declared content type.
 */
@Component
public class FfcClient {

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public FfcClient() {
        this.http = RestClient.builder()
                .baseUrl("https://fantasyfootballcalculator.com/api/v1")
                .build();
    }

    /** @param format "standard" | "half-ppr" | "ppr" | "2qb" | "dynasty" | "rookie" */
    @SuppressWarnings("unchecked")
    public Map<String, Object> adp(String format, int teams, int year) {
        String body = http.get()
                .uri(b -> b.path("/adp/{format}")
                        .queryParam("teams", "{teams}")
                        .queryParam("year", "{year}")
                        .queryParam("position", "all")
                        .build(format, teams, year))
                .retrieve()
                .body(String.class);
        try {
            return json.readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("FFC response was not valid JSON: " + e.getMessage(), e);
        }
    }
}
