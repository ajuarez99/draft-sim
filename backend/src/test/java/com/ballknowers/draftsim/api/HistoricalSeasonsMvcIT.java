package com.ballknowers.draftsim.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 053 T014: {@code GET /api/history/seasons}. A missing {@code X-Sleeper-User} is a 401 (plan F18); the body
 * is the offered seasons ascending, each carrying the era disclosure (counted stats, excluded stats with
 * reasons, coverage) and the settings source line. The list is whatever this database has imported, so the
 * shape is asserted for every entry and nothing is assumed about how many there are.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricalSeasonsMvcIT {

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping historical seasons IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void aMissingIdentityHeaderIs401() throws Exception {
        mvc.perform(get("/api/history/seasons")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/history/seasons").header("X-Sleeper-User", " ")).andExpect(status().isUnauthorized());
    }

    /** Spec 056 T021: sport is required with no default; a defaulted sport asserts a rule. */
    @Test
    void aMissingOrUnknownSportIs400WithNoDefault() throws Exception {
        mvc.perform(get("/api/history/seasons").header("X-Sleeper-User", "it-user"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/history/seasons?sport=").header("X-Sleeper-User", "it-user"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/history/seasons?sport=MLB").header("X-Sleeper-User", "it-user"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/history/seasons?sport=nba").header("X-Sleeper-User", "it-user"))
                .andExpect(status().isBadRequest());   // case-sensitive: the stored codes are NBA / NFL
        // identity is still checked first
        mvc.perform(get("/api/history/seasons")).andExpect(status().isUnauthorized());
    }

    @Test
    void footballRowsCarryTheirSportAndDataNotesAndNeverMixWithBasketball() throws Exception {
        MvcResult r = mvc.perform(get("/api/history/seasons?sport=NFL").header("X-Sleeper-User", "it-user"))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode s : json.readTree(r.getResponse().getContentAsString())) {
            assertEquals("NFL", s.get("sport").asText());
            assertEquals("NFLVERSE", s.get("source").asText());
            assertTrue(s.get("seasonStart").asInt() >= 2000 && s.get("seasonStart").asInt() <= 2025);
            assertTrue(s.get("dataNotes").isArray());
            assertEquals("PRIOR_SEASON_POINTS", s.get("rankingSource").asText());
            assertTrue(s.get("settingsSource").asText().contains("full PPR"));
        }
    }

    @Test
    void theOfferedSeasonsCarryTheDisclosureAndStayInTheOfferedRange() throws Exception {
        MvcResult r = mvc.perform(get("/api/history/seasons?sport=NBA").header("X-Sleeper-User", "it-user"))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = json.readTree(r.getResponse().getContentAsString());
        assertTrue(list.isArray());
        int previous = 0;
        for (JsonNode s : list) {
            assertEquals("NBA", s.get("sport").asText());
            assertTrue(s.get("dataNotes").isArray() && s.get("dataNotes").isEmpty(), "basketball has no data notes");
            int start = s.get("seasonStart").asInt();
            assertTrue(start >= 1970 && start <= 2025, "outside the offered range: " + start);
            assertTrue(start > previous, "ascending");
            previous = start;
            assertTrue(s.get("label").asText().startsWith(String.valueOf(start)));
            assertTrue(s.get("source").asText().equals("KAGGLE") || s.get("source").asText().equals("SLEEPER"));
            assertTrue(s.get("countedStats").isArray() && s.get("countedStats").size() > 0);
            assertTrue(s.get("excludedStats").isObject());
            assertTrue(s.get("statCoverage").isObject());
            assertEquals("PRIOR_SEASON_POINTS", s.get("rankingSource").asText());
            assertEquals("App standard 12-team NBA roster · Legacy Ball Knowers scoring",
                    s.get("settingsSource").asText());
            // an excluded stat never also counts
            for (JsonNode counted : s.get("countedStats")) {
                assertFalse(s.get("excludedStats").has(counted.asText()), counted + " is both counted and excluded");
            }
        }
    }
}
