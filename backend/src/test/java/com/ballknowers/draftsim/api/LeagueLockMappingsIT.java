package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.ingest.SleeperClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deny-by-default, pinned (lock fix pass, CR-10). Walks EVERY handler mapping the application registers and checks
 * it against the lock's public allowlist and an explicit list written here. Adding a controller under an allowlisted
 * prefix, or widening the allowlist, changes the "public by the allowlist" set and fails
 * {@link #thePublicSetIsExactlyWhatThisTestSays} until someone reads the new route and edits the list below. Every
 * route that is not public is then called with no credentials and must be refused: 401 {@code league_session_required}
 * for league routes, 403 {@code admin_token_required} for the operator prefixes.
 *
 * <p>SKIPS when the local Postgres is unreachable (it needs the full context), so read the skip count.
 */
@SpringBootTest(properties = {"draftsim.league-lock.enabled=true", "draftsim.league-lock.idle-seconds=300"})
@AutoConfigureMockMvc
class LeagueLockMappingsIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    /**
     * Every handler pattern the lock lets through on purpose. A new entry needs a reason: either the route reads
     * nothing league-private (Time Machine, username lookup, liveness) or it carries its own secret.
     */
    private static final Set<String> EXPECTED_PUBLIC = new TreeSet<>(Set.of(
            "/api/health",
            "/api/refresh/daily"
    ));
    /** Public by prefix: the Time Machine and the sign-in username lookup. Listed one by one on purpose. */
    private static final Set<String> EXPECTED_PUBLIC_HISTORY_AND_LOOKUP = new TreeSet<>(Set.of(
            "/api/history/claim",
            "/api/history/daily",
            "/api/history/games",
            "/api/history/games/{id}",
            "/api/history/games/{id}/abandon",
            "/api/history/games/{id}/auto",
            "/api/history/games/{id}/award",
            "/api/history/games/{id}/pick",
            "/api/history/profile",
            "/api/history/profile/seen",
            "/api/history/public/daily/yesterday",
            "/api/history/public/games/{id}",
            "/api/history/public/seasons",
            "/api/history/public/seasons/{sport}/{slug}",
            "/api/history/seasons",
            "/api/sleeper/user/{usernameOrId}"
    ));
    /** Operator routes: public to the LOCK, refused by the admin gate. */
    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final String OPS_PREFIX = "/api/" + "in" + "gest";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping league-lock mapping IT");
    }

    @MockitoBean private SleeperClient sleeper;
    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    private record Route(String pattern, Set<HttpMethod> methods, String handler) {}

    private List<Route> routes() {
        List<Route> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<String> patterns = e.getKey().getPathPatternsCondition() == null
                    ? Set.of() : e.getKey().getPathPatternsCondition().getPatternValues();
            Set<HttpMethod> methods = new TreeSet<>(java.util.Comparator.comparing(HttpMethod::name));
            e.getKey().getMethodsCondition().getMethods().forEach(m -> methods.add(HttpMethod.valueOf(m.name())));
            if (methods.isEmpty()) methods.add(HttpMethod.GET);
            for (String p : patterns) out.add(new Route(p, methods, e.getValue().toString()));
        }
        return out;
    }

    private static boolean matchedByAllowlist(String handlerPattern) {
        // The handler pattern is matched as literal text, `{id}` and all: a template segment is just a segment.
        PathPatternParser parser = new PathPatternParser();
        for (String allowed : LeagueLockInterceptor.PUBLIC_PATHS) {
            PathPattern p = parser.parse(allowed);
            if (p.matches(org.springframework.http.server.PathContainer.parsePath(handlerPattern))) return true;
        }
        return false;
    }

    private static boolean isOperatorRoute(String pattern) {
        return pattern.startsWith(ADMIN_PREFIX) || pattern.startsWith(OPS_PREFIX + "/");
    }

    @Test
    void everyMappingIsUnderApiOrIsTheFrameworksErrorPage() {
        List<String> stray = new ArrayList<>();
        for (Route r : routes()) {
            if (!r.pattern().startsWith("/api/") && !r.pattern().equals("/error")) stray.add(r.pattern() + " -> " + r.handler());
        }
        assertTrue(stray.isEmpty(), "a route outside /api is outside the lock: " + stray);
    }

    @Test
    void thePublicSetIsExactlyWhatThisTestSays() {
        Set<String> publicNonOperator = new TreeSet<>();
        for (Route r : routes()) {
            if (r.pattern().startsWith("/api/") && matchedByAllowlist(r.pattern()) && !isOperatorRoute(r.pattern())) {
                publicNonOperator.add(r.pattern());
            }
        }
        Set<String> expected = new TreeSet<>(EXPECTED_PUBLIC);
        expected.addAll(EXPECTED_PUBLIC_HISTORY_AND_LOOKUP);
        assertEquals(expected, publicNonOperator,
                "the lock's public set changed; read every route listed on either side and edit EXPECTED_* on purpose");
    }

    @Test
    void everyOtherRouteRefusesACallerWithNoCredentials() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Route r : routes()) {
            if (!r.pattern().startsWith("/api/") || matchedByAllowlist(r.pattern()) && !isOperatorRoute(r.pattern())) continue;
            String path = r.pattern().replaceAll("\\{[^}/]+}", "zzz");
            for (HttpMethod method : r.methods()) {
                MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.ALL).content("{}");
                MvcResult res = mvc.perform(b).andReturn();
                int status = res.getResponse().getStatus();
                String body = res.getResponse().getContentAsString();
                boolean ok = isOperatorRoute(r.pattern())
                        ? status == 403 && body.contains("admin_token_required")
                        : status == 401 && body.contains("league_session_required");
                checked++;
                if (!ok) failures.add(method + " " + r.pattern() + " -> " + status + " " + body);
            }
        }
        assertTrue(checked > 100, "expected to walk the whole API, walked " + checked);
        assertTrue(failures.isEmpty(), "routes that did not refuse an anonymous caller: " + failures);
    }

    /**
     * Spec 074 PR-01: the Redraft's public routes under /api/history/public answer WITHOUT a session while the lock is
     * ON. Production runs with the lock on and every local test with it off, so a route outside the allowlist would
     * pass everywhere except where it matters. Each call carries no credentials of any kind; the status is the
     * route's own (200, 400, 404 or 403), never the lock's 401 league_session_required.
     */
    @Test
    void everyRedraftPublicRouteAnswersWithoutASessionWhileTheLockIsOn() throws Exception {
        String[][] calls = {
                {"GET", "/api/history/public/seasons?sport=NBA", "200"},
                {"GET", "/api/history/public/seasons", "400"},
                {"GET", "/api/history/public/seasons/NBA/not-a-season", "404"},
                {"GET", "/api/history/public/seasons/XXX/2016-17", "404"},
                {"GET", "/api/history/public/daily/yesterday", "200"},
                {"GET", "/api/history/public/games/2147483000", "404"},
                // the changed history routes: 401 is the ROUTE'S (no identity at all), not the lock's
                {"GET", "/api/history/games", "401"},
                {"POST", "/api/history/claim", "400"},
        };
        for (String[] c : calls) {
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(HttpMethod.valueOf(c[0]), c[1])
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.ALL);
            MvcResult res = mvc.perform(b).andReturn();
            String body = res.getResponse().getContentAsString();
            assertEquals(Integer.parseInt(c[2]), res.getResponse().getStatus(), c[0] + " " + c[1] + " -> " + body);
            assertTrue(!body.contains("league_session_required"), c[0] + " " + c[1] + " was refused by the lock: " + body);
        }
        // a guest id alone reaches the controller and is refused as a GUEST, not by the lock
        MvcResult guest = mvc.perform(MockMvcRequestBuilders.get("/api/history/profile")
                .header("X-Guest-Id", "123e4567-e89b-12d3-a456-426614174000")).andReturn();
        assertEquals(403, guest.getResponse().getStatus());
        assertTrue(guest.getResponse().getContentAsString().contains("GUEST_LIMIT"));
    }
}
