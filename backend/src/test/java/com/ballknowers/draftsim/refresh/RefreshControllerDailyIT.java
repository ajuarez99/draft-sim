package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.api.ApiTokenFilter;
import com.ballknowers.draftsim.config.ApiSecurityProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * specs/009-auto-data-refresh T040, the daily half: real Postgres for
 * {@code daily_capture}, Sleeper- and FFC-facing services mocked. A secret AND an
 * API token are both configured, which exercises the contract's claim that
 * {@code /api/refresh/daily} works without the bearer token while a league route
 * without one is still 401. The no-secret case is {@link RefreshControllerDailyOffIT}.
 *
 * <p>Calls the controller and the filter directly, like {@code RefreshControllerIT}.
 * SKIPS when the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest(properties = {"refresh.secret=it-009-secret", "draftsim.security.token=it-009-token"})
class RefreshControllerDailyIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping daily refresh IT");
    }

    @MockitoBean private PlayerIngestService players;
    @MockitoBean private BoardRefresh boardRefresh;
    @Autowired private RefreshController controller;
    @Autowired private ApiSecurityProperties security;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Today's rows would make PLAYERS skip; the shared local DB may already hold some.
        clear();
        when(players.ingest(any())).thenReturn(new PlayerIngestService.Result(7, 3, true, 0));
        when(boardRefresh.run(any())).thenReturn(new BoardRefresh.Result(
                new FfcAdpService.Result(true, 1, 1, 0, 1, false, null, List.of()),
                new BoardService.Result(5, 3, 2, 0, 0), 1));
    }

    @AfterEach
    void clear() {
        // Rows are for today's UTC date only; a real capture row for today is recreated by the next daily run.
        jdbc.update("delete from daily_capture where capture_date = ?",
                java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
    }

    @Test
    void aWrongOrMissingSecretIs401AndRunsNothing() {
        assertEquals(401, controller.daily("wrong").getStatusCode().value());
        assertEquals(401, controller.daily(null).getStatusCode().value());
        assertEquals(401, controller.daily("").getStatusCode().value());
        verifyNoInteractions(players);
        verifyNoInteractions(boardRefresh);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRightSecretIs200WithAStepPerSportAndKind() {
        ResponseEntity<?> response = controller.daily("it-009-secret");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), body.get("date"));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");
        assertEquals(Sport.values().length * 2, steps.size());
        assertEquals(Sport.values()[0].code(), steps.get(0).get("sport"));
        assertEquals("PLAYERS", steps.get(0).get("kind"));
        assertEquals("DONE", steps.get(0).get("outcome"));
        assertEquals("BOARD", steps.get(1).get("kind"));

        // A second call the same day skips PLAYERS (the row was really written) and still answers 200.
        Map<String, Object> again = (Map<String, Object>) controller.daily("it-009-secret").getBody();
        List<Map<String, Object>> steps2 = (List<Map<String, Object>>) again.get("steps");
        assertEquals("SKIPPED_ALREADY_TODAY", steps2.get(0).get("outcome"));
        assertNull(steps2.get(0).get("detail"), "a nullable detail must survive in the body");
        verify(players, times(Sport.values().length)).ingest(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedStepIs500WithTheBodyStillPresent() {
        when(boardRefresh.run(Sport.NFL)).thenThrow(new IllegalStateException("no blended board"));

        ResponseEntity<?> response = controller.daily("it-009-secret");

        assertEquals(500, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");
        assertTrue(steps.stream().anyMatch(s -> "FAILED".equals(s.get("outcome"))));
    }

    @Test
    void theDailyRouteBypassesTheBearerTokenButALeagueRouteDoesNot() throws Exception {
        assertTrue(security.enabled(), "the IT must run with a token configured");
        ApiTokenFilter filter = new ApiTokenFilter(security);

        MockHttpServletRequest daily = new MockHttpServletRequest("POST", "/api/refresh/daily");
        MockHttpServletResponse dailyResponse = new MockHttpServletResponse();
        FilterChain dailyChain = mock(FilterChain.class);
        filter.doFilter(daily, dailyResponse, dailyChain);
        verify(dailyChain).doFilter(daily, dailyResponse);

        MockHttpServletRequest league = new MockHttpServletRequest("GET", "/api/leagues/x/superlatives");
        MockHttpServletResponse leagueResponse = new MockHttpServletResponse();
        FilterChain leagueChain = mock(FilterChain.class);
        filter.doFilter(league, leagueResponse, leagueChain);
        assertEquals(401, leagueResponse.getStatus());
        verifyNoInteractions(leagueChain);

        // /api/refresh/players is NOT exempt: it goes through the same gate as the web app's calls.
        MockHttpServletRequest playersReq = new MockHttpServletRequest("POST", "/api/refresh/players");
        MockHttpServletResponse playersResponse = new MockHttpServletResponse();
        filter.doFilter(playersReq, playersResponse, mock(FilterChain.class));
        assertEquals(401, playersResponse.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePlayersRouteRunsOneSportAndSkipsTheSecondCallToday() {
        Map<String, Object> first = (Map<String, Object>) controller.players(Sport.NBA.code(), "it-user").getBody();
        assertEquals("DONE", first.get("outcome"));
        Map<String, Object> second = (Map<String, Object>) controller.players(Sport.NBA.code(), "it-user").getBody();
        assertEquals("SKIPPED_ALREADY_TODAY", second.get("outcome"));
        assertNull(second.get("detail"));
        verify(players, times(1)).ingest(Sport.NBA);
        verifyNoInteractions(boardRefresh);
    }
}
