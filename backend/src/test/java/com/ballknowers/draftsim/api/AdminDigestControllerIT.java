package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/043 T016: the real HTTP surface of {@code /api/admin/digest} against the local Postgres, through
 * the real admin interceptor and the app's own ObjectMapper. Read-only: no row is inserted. SKIPS when
 * the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminDigestControllerIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping digest IT");
    }

    @Autowired private MockMvc mvc;

    @Test
    void noTokenIsRefused() throws Exception {
        mvc.perform(get("/api/admin/digest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_token_required"));
        mvc.perform(post("/api/admin/digest/refresh-stats"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_token_required"));
        mvc.perform(get("/api/admin/digest").header("X-Admin-Token", "not-the-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenGetsBasketballThenFootballInTheContractShape() throws Exception {
        mvc.perform(get("/api/admin/digest").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.yesterday").isString())
                .andExpect(jsonPath("$.sports.length()").value(2))
                .andExpect(jsonPath("$.sports[0].sport").value("nba"))
                .andExpect(jsonPath("$.sports[1].sport").value("nfl"))
                .andExpect(jsonPath("$.sports[0].pointsBasis").value("Sleeper default points"))
                .andExpect(jsonPath("$.sports[1].pointsBasis").value("Sleeper PPR"))
                .andExpect(jsonPath("$.sports[0].status").isString())
                .andExpect(jsonPath("$.sports[0].topPerformers").isArray())
                .andExpect(jsonPath("$.sports[0].keyPlayers").isArray())
                .andExpect(jsonPath("$.sports[0].positionLeaders").doesNotExist())
                .andExpect(jsonPath("$.sports[1].positionLeaders").isArray());
    }

    @Test
    void anExplicitDateIsAccepted() throws Exception {
        mvc.perform(get("/api/admin/digest").param("date", "2026-04-12").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sports[0].season").value(2025))
                .andExpect(jsonPath("$.sports[0].trending.status").value("TRENDING_NOT_HISTORICAL"))
                // The 2025 finale is stored, final and long past: a READY night exercises every read
                // (rows, scoring, search rank, expectations) through real SQL.
                .andExpect(jsonPath("$.sports[0].status").value("READY"))
                .andExpect(jsonPath("$.sports[0].topPerformers.length()").value(10))
                .andExpect(jsonPath("$.sports[0].topPerformers[0].points").isNumber())
                .andExpect(jsonPath("$.sports[0].keyPlayers").isArray());
    }

    @Test
    void aMalformedDateIsA400WithItsCode() throws Exception {
        mvc.perform(get("/api/admin/digest").param("date", "bad").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_date"));
        mvc.perform(get("/api/admin/digest").param("date", "2026-13-40").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_date"));
    }
}
