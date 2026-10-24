package com.ballknowers.draftsim.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Yahoo Fantasy Basketball's public draft analysis (spec 046 R2, amended F16): average pick, average
 * auction cost and the share of leagues that drafted the player, across Yahoo's public leagues. Server-side
 * only -- the endpoint sends no {@code Access-Control-Allow-Origin}, so a browser can't call it.
 *
 * <p>Measured 2026-10-21/23: 200 JSON with no auth; 300 players fetched, 186 with a numeric ADP, tops out
 * near 123. Not an official API, so every assumption is checked and a surprise throws (the caller keeps
 * the previous capture):
 * <ul>
 *   <li>{@code ?format=json_f} is required or the endpoint answers XML.</li>
 *   <li>Every numeric field is a STRING, and {@code "-"} means none. Parsed to null, never 0.</li>
 *   <li>Reads {@code average_pick} / {@code average_cost} / {@code percent_drafted}, NOT the
 *       {@code preseason_*} siblings (those are the full-history values).</li>
 *   <li>{@code fantasy_content.league.players} must exist or the whole fetch is rejected.</li>
 * </ul>
 */
@Component
public class YahooAdpClient {

    static final String LEAGUE_URL = "https://pub-api-ro.fantasysports.yahoo.com/fantasy/v2/league/nba.l.public";
    static final int PAGE_SIZE = 25;
    /** Hard cap on players read, whatever the pages say (the stop rule normally ends it near 9 calls). */
    static final int MAX_PLAYERS = 400;
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** One Yahoo player with a numeric average pick. {@code averageCost} / {@code percentDrafted} may be null. */
    public record Player(String name, String team, String position, double averagePick,
                         Double averageCost, Double percentDrafted) {}

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public YahooAdpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * The exact URL for one page. Spelled out and asserted in a unit test (F16): the {@code ;} matrix
     * parameters and the {@code /} in {@code settings/players} are part of the route, and a URI-template
     * encoder is free to mangle them.
     */
    static String pageUrl(int start) {
        return LEAGUE_URL + ";out=settings/players;position=ALL;start=" + start + ";count=" + PAGE_SIZE
                + ";sort=DA_AP;out=draft_analysis?format=json_f";
    }

    /** Every Yahoo-ranked player, best average pick first. Throws on any transport or shape failure. */
    public List<Player> fetchAll() {
        // URI.create, not a template: RestClient would otherwise re-encode the path.
        return fetchAll(start -> http.get().uri(URI.create(pageUrl(start))).retrieve().body(String.class));
    }

    /** The paging loop over an injectable page source, so tests feed fixture pages. */
    List<Player> fetchAll(IntFunction<String> pageBody) {
        List<Player> out = new ArrayList<>();
        for (int start = 0; start < MAX_PLAYERS; start += PAGE_SIZE) {
            List<Player> page = parsePage(pageBody.apply(start));
            if (page.isEmpty()) break;   // first page with no numeric average_pick: the ranked list is over
            out.addAll(page);
        }
        return out.size() > MAX_PLAYERS ? new ArrayList<>(out.subList(0, MAX_PLAYERS)) : out;
    }

    /** Players with a numeric {@code average_pick} on one page; unranked entries are left out. */
    List<Player> parsePage(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Yahoo response was not valid JSON: " + e.getMessage(), e);
        }
        JsonNode players = root == null ? null : root.path("fantasy_content").path("league").path("players");
        if (players == null || !players.isArray()) {
            throw new IllegalStateException(
                    "Yahoo response had no fantasy_content.league.players -- the response shape changed");
        }
        List<Player> out = new ArrayList<>();
        for (JsonNode entry : players) {
            JsonNode p = entry.path("player");
            if (p.isMissingNode()) continue;   // the trailing {"count": N} style entries
            JsonNode analysis = p.path("draft_analysis");
            Double adp = number(analysis.path("average_pick"));
            if (adp == null) continue;
            out.add(new Player(
                    p.path("name").path("full").asText(null),
                    p.path("editorial_team_abbr").asText(null),
                    p.path("display_position").asText(null),
                    adp,
                    number(analysis.path("average_cost")),
                    number(analysis.path("percent_drafted"))));
        }
        return out;
    }

    /** Yahoo sends numbers as strings and {@code "-"} for none. Null for anything that isn't a finite number. */
    static Double number(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        String t = n.asText("").trim();
        if (t.isEmpty() || t.equals("-")) return null;
        try {
            double v = Double.parseDouble(t);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
