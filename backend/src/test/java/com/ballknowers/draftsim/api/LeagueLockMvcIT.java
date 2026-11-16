package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.config.AdminProperties;
import com.ballknowers.draftsim.config.LeagueLockProperties;
import com.ballknowers.draftsim.config.LeagueSession;
import com.ballknowers.draftsim.ingest.SleeperClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The league API lock with the lock ON (spec 073 follow-up, second pass), through the real filter chain,
 * interceptors, CORS mapping and controllers. SKIPS when the local Postgres is unreachable, so the caller must
 * read the skip count. Sleeper is mocked; no league rows are needed because a refused request never reaches a
 * handler and an unknown id is a plain 404.
 */
@SpringBootTest(properties = {"draftsim.league-lock.enabled=true", "draftsim.league-lock.idle-seconds=300"})
@AutoConfigureMockMvc
class LeagueLockMvcIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String SESSION = "X-League-Session";
    private static final String USER = "it-lock-user";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping league-lock IT");
    }

    @MockitoBean private SleeperClient sleeper;
    @Autowired private MockMvc mvc;
    @Autowired private LeagueSession sessions;

    private static MockHttpServletRequestBuilder withUser(MockHttpServletRequestBuilder b) {
        return b.header("X-Sleeper-User", USER);
    }

    private void assertLocked(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("league_session_required"));
    }

    // ---------------------------------------------------------------- locked by default, one route per family

    @Test
    void everyLeagueRouteFamilyRefusesWithoutASession() throws Exception {
        assertLocked(withUser(get("/api/drafts")));                                  // LeagueController
        assertLocked(withUser(get("/api/drafts/zzz/board")));                        // drafts
        assertLocked(withUser(get("/api/leagues/zzz/history")));                     // leagues (history)
        assertLocked(withUser(get("/api/leagues/zzz/analysis")));                    // leagues (analysis)
        assertLocked(withUser(get("/api/mocks/999999")));                            // mocks
        assertLocked(withUser(post("/api/mocks/from-draft/zzz")));                   // mocks
        assertLocked(withUser(put("/api/managers/1/tendencies")));                   // managers
        assertLocked(withUser(post("/api/sims/stream")));                            // sims
        assertLocked(withUser(post("/api/setup/league/zzz")));                       // setup
        assertLocked(withUser(get("/api/targets")));                                 // targets
        assertLocked(withUser(get("/api/sleeper/users/123/leagues")));               // league data, not the lookup
        assertLocked(withUser(post("/api/leagues/zzz/refresh")));                    // refresh for a league
        assertLocked(withUser(get("/api/leagues/zzz/refresh")));
        assertLocked(withUser(post("/api/refresh/players?sport=nfl")));              // no secret of its own: stays locked
        assertLocked(get("/api/league-session"));
    }

    @Test
    void theRefusalIsIdenticalForMissingMalformedExpiredAndBadlySignedSessions() throws Exception {
        LeagueSession past = new LeagueSession(new AdminProperties(TestAdmin.TOKEN),
                new LeagueLockProperties(true, 300, 43200), Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
        String valid = sessions.issue();
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");
        String body = null;
        for (String token : new String[] {null, "", "  ", "garbage", "1.2", past.issue(), tampered}) {
            var request = withUser(get("/api/drafts"));
            if (token != null) request.header(SESSION, token);
            MvcResult r = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
            assertNull(r.getResponse().getHeader(SESSION), "a refusal must not hand out a session");
            String got = r.getResponse().getContentAsString();
            if (body == null) body = got;
            assertEquals(body, got, "same refusal body for every kind of bad session");
        }
        assertTrue(body.contains("league_session_required"));
    }

    // ---------------------------------------------------------------- a valid session passes and slides

    @Test
    void aValidSessionPassesAndTheResponseCarriesAFreshValidSession() throws Exception {
        String token = sessions.issue();
        MvcResult r = mvc.perform(withUser(get("/api/drafts")).header(SESSION, token))
                .andExpect(status().isOk())
                .andReturn();
        String refreshed = r.getResponse().getHeader(SESSION);
        assertNotNull(refreshed);
        assertTrue(sessions.valid(refreshed));
    }

    @Test
    void theSessionRouteAnswers204WithARefreshedHeader() throws Exception {
        String token = sessions.issue();
        MvcResult r = mvc.perform(get("/api/league-session").header(SESSION, token))
                .andExpect(status().isNoContent())
                .andReturn();
        assertTrue(sessions.valid(r.getResponse().getHeader(SESSION)));
    }

    @Test
    void anExpiredSessionIsRefused() throws Exception {
        LeagueSession past = new LeagueSession(new AdminProperties(TestAdmin.TOKEN),
                new LeagueLockProperties(true, 300, 43200), Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
        assertLocked(withUser(get("/api/drafts")).header(SESSION, past.issue()));
    }

    @Test
    void theAdminTokenAloneAlsoPassesAndNoSessionIsHandedOut() throws Exception {
        MvcResult r = mvc.perform(withUser(get("/api/drafts")).header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        assertNull(r.getResponse().getHeader(SESSION));
        assertLocked(withUser(get("/api/drafts")).header("X-Admin-Token", "wrong"));
    }

    // ---------------------------------------------------------------- stream ticket: live-stream only, one draft

    private String ticketFor(String draftId) throws Exception {
        MvcResult r = mvc.perform(post("/api/league-session/stream-ticket").param("draftId", draftId)
                        .header(SESSION, sessions.issue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(60))
                .andReturn();
        // A ticket request is answered with a renewed SESSION (it was authorised by one), never a ticket-derived one.
        assertTrue(sessions.valid(r.getResponse().getHeader(SESSION)));
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getResponse().getContentAsString())
                .get("ticket").asText();
    }

    @Test
    void aTicketIsAcceptedOnItsOwnDraftsStreamAndMintsNoSession() throws Exception {
        String ticket = ticketFor("zzz");
        // Unknown draft: the handler answers 404, which proves the lock let it through (not 401).
        MvcResult r = mvc.perform(get("/api/drafts/zzz/live-stream").param("user", USER).param("ticket", ticket))
                .andExpect(status().isNotFound()).andReturn();
        assertNull(r.getResponse().getHeader(SESSION), "a ticket must not be traded for a session");
    }

    @Test
    void aTicketIsRefusedOnAnotherDraftsStreamAndOnEveryOtherRoute() throws Exception {
        String ticket = ticketFor("zzz");
        assertLocked(get("/api/drafts/other/live-stream").param("user", USER).param("ticket", ticket));
        assertLocked(withUser(get("/api/drafts")).param("ticket", ticket));
        assertLocked(withUser(get("/api/drafts/zzz/board")).param("ticket", ticket));
        assertLocked(withUser(get("/api/leagues/zzz/history")).param("ticket", ticket));
        assertLocked(withUser(get("/api/league-session")).param("ticket", ticket));
        assertLocked(post("/api/league-session/stream-ticket").param("draftId", "zzz").param("ticket", ticket));
    }

    @Test
    void aTicketIsNeverAcceptedAsASessionHeader() throws Exception {
        String ticket = ticketFor("zzz");
        assertLocked(withUser(get("/api/drafts")).header(SESSION, ticket));
        assertLocked(get("/api/drafts/zzz/live-stream").param("user", USER).header(SESSION, ticket));
    }

    @Test
    void aSessionTokenIsNeverAcceptedInTheQueryString() throws Exception {
        String session = sessions.issue();
        for (String param : new String[] {"session", "ticket", "token", "x-league-session"}) {
            assertLocked(get("/api/drafts/zzz/live-stream").param("user", USER).param(param, session));
            assertLocked(withUser(get("/api/drafts")).param(param, session));
        }
    }

    @Test
    void anExpiredTicketIsRefusedAndABadOneLooksTheSame() throws Exception {
        LeagueSession past = new LeagueSession(new AdminProperties(TestAdmin.TOKEN),
                new LeagueLockProperties(true, 300, 43200), Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
        assertLocked(get("/api/drafts/zzz/live-stream").param("user", USER).param("ticket", past.issueTicket("zzz")));
        for (String bad : new String[] {"", "  ", "t.", "t.1.2", "garbage"}) {
            assertLocked(get("/api/drafts/zzz/live-stream").param("user", USER).param("ticket", bad));
        }
        assertLocked(get("/api/drafts/zzz/live-stream").param("user", USER));
    }

    @Test
    void theTicketRouteIsLockedAndNeedsADraftId() throws Exception {
        assertLocked(post("/api/league-session/stream-ticket").param("draftId", "zzz"));
        mvc.perform(post("/api/league-session/stream-ticket").header(SESSION, sessions.issue()))
                .andExpect(status().isBadRequest());
        // The admin token (curl) may ask for one too.
        mvc.perform(post("/api/league-session/stream-ticket").param("draftId", "zzz")
                        .header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticket").isString());
    }

    @Test
    void theAdminTokenStillPassesTheStreamRouteAndSoDoesAHeaderSession() throws Exception {
        mvc.perform(get("/api/drafts/zzz/live-stream").param("user", USER).header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/zzz/live-stream").param("user", USER).header(SESSION, sessions.issue()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSessionPastItsAbsoluteLifetimeIsRefusedEvenWithAFutureExpiry() throws Exception {
        // Unlocked 12h + 1 min ago (cap 12h), renewed a moment ago: signature good, expiry in the future, cap passed.
        Instant now = Instant.now();
        Instant origin = now.minusSeconds(43200 + 60);
        LeagueSession chain = new LeagueSession(new AdminProperties(TestAdmin.TOKEN),
                new LeagueLockProperties(true, 300, 86400), Clock.fixed(origin, ZoneOffset.UTC));
        String token = chain.issue();
        for (Instant t = origin.plusSeconds(200); t.isBefore(now); t = t.plusSeconds(200)) {
            token = new LeagueSession(new AdminProperties(TestAdmin.TOKEN),
                    new LeagueLockProperties(true, 300, 86400), Clock.fixed(t, ZoneOffset.UTC)).renew(token);
        }
        // The chain used a 24h cap to build it; the live server's cap is 12h, and refuses it.
        assertLocked(withUser(get("/api/drafts")).header(SESSION, token));
    }

    // ---------------------------------------------------------------- the public allowlist stays as it is today

    @Test
    void theAllowlistedRoutesAnswerWithoutASession() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        // Time Machine: reaches its own handler (identity check, sport check), never the lock.
        mvc.perform(get("/api/history/seasons").param("sport", "NBA").header("X-Sleeper-User", USER))
                .andExpect(status().isOk());
        mvc.perform(get("/api/history/seasons")).andExpect(status().isUnauthorized())   // the handler's own identity rule
                .andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(get("/api/history/daily").header("X-Sleeper-User", USER)).andExpect(status().isOk());
        mvc.perform(get("/api/history/profile").header("X-Sleeper-User", USER)).andExpect(status().isOk());
        mvc.perform(get("/api/history/games").header("X-Sleeper-User", USER)).andExpect(status().isOk());
        mvc.perform(get("/api/history/games/999999999").header("X-Sleeper-User", USER))
                .andExpect(status().isNotFound());
        // Sign-in's username lookup: reaches the (mocked) client, answers found:false.
        org.mockito.Mockito.when(sleeper.user("nobody")).thenReturn(null);
        mvc.perform(get("/api/sleeper/user/nobody")).andExpect(status().isOk()).andExpect(jsonPath("$.found").value(false));
        // The daily-refresh job has its own secret: no secret configured in tests -> the route's own answer, not the lock's.
        mvc.perform(post("/api/refresh/daily")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
        // Operator routes: the admin gate, not the lock, refuses.
        mvc.perform(post("/api/ingest/players?sport=nfl")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_token_required"));
        mvc.perform(post("/api/admin/session")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_token_required"));
    }

    // ---------------------------------------------------------------- POST /api/admin/session

    @Test
    void theSessionEndpointTradesTheAdminTokenForAWorkingSession() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/session").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.token").isString())
                .andReturn();
        String body = r.getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("token").asText();
        assertTrue(sessions.valid(token));
        mvc.perform(withUser(get("/api/drafts")).header(SESSION, token)).andExpect(status().isOk());
        for (String bad : new String[] {"wrong", "", "  "}) {
            mvc.perform(post("/api/admin/session").header("X-Admin-Token", bad))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("admin_token_required"));
        }
    }

    // ---------------------------------------------------------------- path-matching bypass shapes

    @Test
    void noPathShapeSlipsAMissingSessionPastTheLock() throws Exception {
        // /api/drafts answers 200 with an identity and no lock (see aValidSessionPasses...), so anything but
        // 401 or 404 here would be a bypass. Spring decides the handler and the exclusion with the same matcher.
        List<String> shapes = List.of(
                "/api/drafts/", "//api/drafts", "/api//drafts", "/api/drafts//", "/api/./drafts",
                "/api/history/../drafts", "/api/health/../drafts", "/api/sleeper/user/../../drafts",
                "/api/history/..%2Fdrafts", "/api/history%2F..%2Fdrafts", "/api%2Fdrafts",
                "/API/drafts", "/api/Drafts", "/api/DRAFTS", "/api/health;/../drafts",
                "/api/drafts;jsessionid=abc", "/api/drafts;x=/health", "/api;x=1/drafts", "/api/drafts%00",
                "/api/drafts.json", "/api/drafts%2F");
        for (String shape : shapes) {
            int code = mvc.perform(withUser(get(java.net.URI.create(shape)))).andReturn().getResponse().getStatus();
            assertTrue(code == 401 || code == 404 || code == 400, shape + " answered " + code);
        }
        // Percent-encoding a letter decodes to the real route, and the real route is locked.
        assertLocked(withUser(get("/api/%64rafts")));
        // A matrix suffix on a locked route is still the locked route.
        assertLocked(withUser(get("/api/drafts;jsessionid=abc")));
        // And on the live stream, the matched pattern (not the raw URI) decides whether ?session counts.
        String token = sessions.issue();
        String ticket = sessions.issueTicket("zzz");
        assertLocked(withUser(get("/api/drafts;a=/live-stream")).param("ticket", ticket));
        assertLocked(withUser(get("/api/drafts/zzz/live-stream/")).param("ticket", "bad"));
    }

    // ---------------------------------------------------------------- CORS

    @Test
    void theSessionHeaderIsAllowedOnPreflightAndExposedOnTheRealResponse() throws Exception {
        mvc.perform(options("/api/drafts")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-league-session,x-sleeper-user"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String allowed = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
                    assertNotNull(allowed);
                    assertTrue(allowed.toLowerCase().contains("x-league-session"), allowed);
                    assertNull(result.getResponse().getHeader(SESSION));
                });

        String token = sessions.issue();
        MvcResult real = mvc.perform(withUser(get("/api/drafts")).header(SESSION, token)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-League-Session"))
                .andReturn();
        assertNotNull(real.getResponse().getHeader(SESSION));

        // Even the refusal is readable cross-origin, so the web can see the 401 and lock itself.
        mvc.perform(withUser(get("/api/drafts")).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        assertFalse(LeagueLockInterceptor.PUBLIC_PATHS.contains("/api/sleeper/**"));
    }
}
