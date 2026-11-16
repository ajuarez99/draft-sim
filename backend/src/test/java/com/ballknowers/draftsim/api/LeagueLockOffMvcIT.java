package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.config.LeagueSession;
import com.ballknowers.draftsim.ingest.SleeperClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The league API lock OFF, which is the default and what the live combined site runs until the deploy-day flip
 * (spec 073 follow-up, second pass). Every route behaves as it did before the lock existed. SKIPS when the
 * local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest   // draftsim.league-lock.enabled defaults to false
@AutoConfigureMockMvc
class LeagueLockOffMvcIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping league-lock-off IT");
    }

    @MockitoBean private SleeperClient sleeper;
    @Autowired private MockMvc mvc;
    @Autowired private LeagueSession sessions;

    @Test
    void leagueRoutesNeedNoSessionAndHandOutNone() throws Exception {
        org.mockito.Mockito.when(sleeper.state(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Map.of("league_season", "2026"));
        for (String path : new String[] {"/api/drafts", "/api/sleeper/users/123/leagues"}) {
            MvcResult r = mvc.perform(get(path).header("X-Sleeper-User", "it-lock-off-user"))
                    .andExpect(status().isOk()).andReturn();
            assertNull(r.getResponse().getHeader("X-League-Session"), path);
        }
        mvc.perform(get("/api/leagues/zzz/history").header("X-Sleeper-User", "it-lock-off-user"))
                .andExpect(status().isNotFound());   // the route's own answer, not the lock's
        // Even a junk session header changes nothing while the lock is off.
        mvc.perform(get("/api/drafts").header("X-Sleeper-User", "it-lock-off-user").header("X-League-Session", "junk"))
                .andExpect(status().isOk());
    }

    @Test
    void theSessionEndpointStillWorksSoTheWebLockScreenBehavesTheSameInDev() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/session").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andReturn();
        assertTrue(r.getResponse().getContentAsString().contains("\"token\""));
        mvc.perform(post("/api/admin/session")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin_token_required"));
        // The renewal route exists and answers 204 with no lock in the way.
        mvc.perform(get("/api/league-session")).andExpect(status().isNoContent());
    }

    /**
     * Live verification V-02 (2026-11-16): with the lock off the backend handed back no renewed token, so the
     * league site's idle timer locked an active user one window after unlock. A valid session is renewed either
     * way; nothing is ever refused while the lock is off.
     */
    @Test
    void aValidSessionIsStillRenewedSoTheLeagueSiteStaysUnlockedWhileActive() throws Exception {
        org.mockito.Mockito.when(sleeper.state(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Map.of("league_season", "2026"));
        String token = sessions.issue();
        for (String path : new String[] {"/api/league-session", "/api/drafts"}) {
            MvcResult r = mvc.perform(get(path).header("X-Sleeper-User", "it-lock-off-user")
                            .header("X-League-Session", token))
                    .andExpect(status().is2xxSuccessful()).andReturn();
            String renewed = r.getResponse().getHeader("X-League-Session");
            assertTrue(renewed != null && sessions.valid(renewed), path + " must answer with a valid renewed session");
        }
        // No session presented: none handed out.
        assertNull(mvc.perform(get("/api/league-session")).andReturn().getResponse().getHeader("X-League-Session"));
    }
}
