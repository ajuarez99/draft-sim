package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A blank {@code ADMIN_TOKEN} means admin is DISABLED and every admin-only route refuses
 * (claude/audit-2026-09-28/01 decision 2). This is the opposite polarity from
 * {@code API_TOKEN}, where blank turns the gate off, so it gets its own test: the failure to
 * fear is a blank secret that "matches" a blank header and opens the ingest routes on a deploy
 * that simply forgot to set the variable.
 *
 * <p>Overrides the test JVM's default token (build.gradle.kts) with a blank. SKIPS when the local
 * Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest(properties = "draftsim.admin.token=")
@AutoConfigureMockMvc
class AdminTokenDisabledIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping admin-disabled IT");
    }

    @MockitoBean private PlayerIngestService playerIngest;
    @Autowired private MockMvc mvc;

    @Test
    void withNoConfiguredTokenEveryIngestRouteRefusesWhateverIsPresented() throws Exception {
        for (String presented : new String[] {null, "", "  ", TestAdmin.TOKEN, "null", "undefined"}) {
            var request = post("/api/ingest/players?sport=nfl");
            if (presented != null) request.header("X-Admin-Token", presented);
            mvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("admin_token_required"));
        }
        verifyNoInteractions(playerIngest);
    }

    /** The wired AdminAccess bean reports the same thing: nobody is admin when nothing is configured. */
    @Autowired private com.ballknowers.draftsim.config.AdminAccess adminAccess;

    @Test
    void theWiredBeanTreatsNoRequestAsAdmin() {
        for (String presented : new String[] {"", TestAdmin.TOKEN}) {
            try (var r = TestAdmin.withToken(presented)) {
                org.junit.jupiter.api.Assertions.assertFalse(adminAccess.isAdmin());
            }
        }
    }
}
