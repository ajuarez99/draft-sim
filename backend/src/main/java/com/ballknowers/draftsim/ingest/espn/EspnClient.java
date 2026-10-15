package com.ballknowers.draftsim.ingest.espn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads ESPN's fantasy API (spec 035 research R1). Same plain {@code java.net.http} style as
 * {@code SleeperClient}.
 *
 * <p>Credential hygiene (plan F14):
 * <ul>
 *   <li>{@link HttpClient.Redirect#NEVER}: a redirect could carry the {@code Cookie} header to a
 *       host that was never configured;</li>
 *   <li>every URL is built from {@code espn.base-url}, and a request whose host differs from that
 *       host is refused before it is sent;</li>
 *   <li>cookies are a per-call argument, sent only when non-null, and never stored on this object;</li>
 *   <li>exceptions are {@link EspnException}s that carry a code, never a request, headers, a body or
 *       a cause (a transport failure's own message can echo a URL).</li>
 * </ul>
 *
 * <p>Methods are overridable on purpose: the ITs stub this class to serve fixtures.
 */
@Component
public class EspnClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String allowedHost;
    private final HttpClient http;

    public EspnClient(@Value("${espn.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.allowedHost = URI.create(this.baseUrl).getHost();
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** One season of one league, with the named {@code view=} parameters. */
    public Map<String, Object> league(String game, String leagueId, int season, Set<String> views,
                                      EspnCredential credential) {
        String url = baseUrl + "/games/" + game + "/seasons/" + season + "/segments/0/leagues/" + leagueId
                + query(views, null);
        return asMap(get(url, credential, null));
    }

    /**
     * Pre-2018 seasons live under {@code leagueHistory} and answer with a one-element array
     * (research R11, unverified: confirm against a real league).
     */
    public Map<String, Object> leagueHistory(String game, String leagueId, int season, Set<String> views,
                                             EspnCredential credential) {
        String url = baseUrl + "/games/" + game + "/leagueHistory/" + leagueId + query(views, "seasonId=" + season);
        Object body = get(url, credential, null);
        if (body instanceof List<?> list) {
            if (list.isEmpty()) throw new EspnNotFound();
            return asMap(list.get(0));
        }
        return asMap(body);
    }

    /** One scoring period's transactions (research R9: the least certain shape in this client). */
    public Map<String, Object> transactions(String game, String leagueId, int season, int scoringPeriodId,
                                            EspnCredential credential) {
        String url = baseUrl + "/games/" + game + "/seasons/" + season + "/segments/0/leagues/" + leagueId
                + query(Set.of("mTransactions2"), "scoringPeriodId=" + scoringPeriodId);
        return asMap(get(url, credential, null));
    }

    /** ESPN's team table, public (research R4). */
    public Map<String, Object> proTeams(String game, int season) {
        String url = baseUrl + "/games/" + game + "/seasons/" + season + query(Set.of("proTeamSchedules_wl"), null);
        return asMap(get(url, null, null));
    }

    /** The public active-player catalogue (research R2), for names/positions/teams by ESPN id. */
    public Map<String, Object> players(String game, int season) {
        String url = baseUrl + "/games/" + game + "/seasons/" + season + "/players" + query(Set.of("players_wl"), null);
        return Map.of("players", asList(get(url, null, "{\"filterActive\":{\"value\":true}}")));
    }

    private static String query(Set<String> views, String extra) {
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (String v : new TreeSet<>(views)) {
            if (!first) sb.append('&');
            sb.append("view=").append(v);
            first = false;
        }
        if (extra != null) {
            if (!first) sb.append('&');
            sb.append(extra);
        }
        return sb.toString();
    }

    private Object get(String url, EspnCredential credential, String fantasyFilter) {
        URI uri = URI.create(url);
        if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(allowedHost)) {
            // Cannot happen from the builders above; this is the backstop the contract promises.
            throw new EspnHttpError(0);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (credential != null) req.header("Cookie", credential.cookieHeader());
        if (fantasyFilter != null) req.header("x-fantasy-filter", fantasyFilter);
        HttpResponse<byte[]> res;
        try {
            res = http.send(req.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new EspnHttpError(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EspnHttpError(0);
        }
        int status = res.statusCode();
        if (status == 401) throw new EspnAccessDenied();
        if (status == 404) throw new EspnNotFound();
        if (status != 200) throw new EspnHttpError(status);
        try {
            return JSON.readValue(res.body(), new TypeReference<Object>() {});
        } catch (IOException e) {
            // A 200 that is not JSON: a shape problem, not a transport one.
            throw new EspnShapeChanged("response");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new EspnShapeChanged("response");
    }

    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) return new ArrayList<>(l);
        throw new EspnShapeChanged("response");
    }
}
