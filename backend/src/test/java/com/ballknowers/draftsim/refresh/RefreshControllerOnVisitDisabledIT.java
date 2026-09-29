package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.PlayerGameIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * specs/009-auto-data-refresh T023, the off half: with
 * {@code refresh.on-visit.enabled=false} a POST behaves like a GET and starts
 * nothing, however stale the league (research R12). Same skip convention as
 * {@link RefreshControllerIT}.
 */
@SpringBootTest(properties = "refresh.on-visit.enabled=false")
class RefreshControllerOnVisitDisabledIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final String LEAGUE = "it-009-refresh-disabled";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping refresh on-visit-disabled IT");
    }

    @MockitoBean private LeagueHistoryIngestService history;
    @MockitoBean private PlayerGameIngestService playerGames;
    @Autowired private RefreshController controller;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tearDown();
        jdbc.update("insert into league (sport, season, sleeper_id, name, total_rosters, status) "
                + "values ('nba', 1998, ?, 'IT refresh disabled', 12, 'in_season')", LEAGUE);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPostStartsNothingWhenRefreshOnVisitIsOff() {
        ResponseEntity<?> response = controller.trigger(LEAGUE, null);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("FRESH", body.get("state"), "the stored state, with nothing running");
        verifyNoInteractions(history);
        verifyNoInteractions(playerGames);
    }
}
