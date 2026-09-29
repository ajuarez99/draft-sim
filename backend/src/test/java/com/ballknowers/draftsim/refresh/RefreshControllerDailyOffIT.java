package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * specs/009-auto-data-refresh T040, the no-secret half: with {@code refresh.secret}
 * blank the daily route does not exist, whatever header is presented (research R8).
 * Same skip convention as {@link RefreshControllerIT}.
 */
@SpringBootTest(properties = "refresh.secret=")
class RefreshControllerDailyOffIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping daily-off IT");
    }

    @MockitoBean private PlayerIngestService players;
    @MockitoBean private BoardRefresh boardRefresh;
    @Autowired private RefreshController controller;

    @Test
    void noSecretConfiguredIs404EvenWithAHeaderAndRunsNothing() {
        assertEquals(404, controller.daily(null).getStatusCode().value());
        assertEquals(404, controller.daily("anything").getStatusCode().value());
        assertEquals(404, controller.daily("").getStatusCode().value());
        verifyNoInteractions(players);
        verifyNoInteractions(boardRefresh);
    }
}
