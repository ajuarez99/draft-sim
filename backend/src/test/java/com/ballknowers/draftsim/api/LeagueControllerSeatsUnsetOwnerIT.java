package com.ballknowers.draftsim.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for LeagueController.seats()'s mySlot field in the
 * default, unconfigured-owner state -- same convention as
 * DraftRepositoryAllWithLeagueIT/DraftRepositoryUpsertPicksIT: no
 * Testcontainers/embedded-DB harness or mocking framework exists in this repo,
 * so this reuses the real Spring context + Flyway-migrated schema against
 * application.yml's local-dev default (localhost:5433/draftsim). Gated so the
 * suite SKIPS (not fails) when that Postgres isn't reachable.
 *
 * This is the direct regression test for the Map.of(...) risk found in
 * claude/plan-review-A.md: seats() used to build its top-level response with
 * Map.of(...), which throws NullPointerException on any null value. mySlot is
 * null in this exact state (no APP_OWNER_SLEEPER_USER_ID configured -- the
 * state every fresh checkout starts in), so asserting only that mySlot "is
 * logically null" would not have caught that bug; this test also forces actual
 * JSON serialization of the response body, which is where Map.of(...) would
 * have thrown.
 *
 * Explicitly overrides draftsim.owner.sleeper-user-id to blank rather than
 * relying on APP_OWNER_SLEEPER_USER_ID being unset in whatever shell runs the
 * suite -- application.yml's default is blank, but a developer who has
 * exported that env var locally (to actually use the feature) would otherwise
 * silently flip this test into the "configured" state it isn't meant to
 * cover, without a single line of test code changing to explain why it
 * started failing (or, worse, matching some other manager and passing for
 * the wrong reason).
 */
@SpringBootTest
@TestPropertySource(properties = "draftsim.owner.sleeper-user-id=")
class LeagueControllerSeatsUnsetOwnerIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping LeagueController integration test");
    }

    @Autowired private LeagueController controller;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private long leagueId;
    private long managerId;
    private String sleeperDraftId;

    @BeforeEach
    void setUp() {
        // Fixture rows scoped under distinct "it-" sleeper ids; wiped up front in case a
        // previous run was interrupted before tearDown ran.
        jdbc.update("delete from league where sleeper_id = ?", "it-league-seats-unset-owner");
        jdbc.update("delete from manager where sleeper_user_id = ?", "it-user-seats-unset-owner");

        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-seats-unset-owner", "IT Manager");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-seats-unset-owner", "IT League", 12);

        sleeperDraftId = "it-draft-seats-unset-owner";
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams,
                                   draft_type, status, start_time, slot_to_manager)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                leagueId, sleeperDraftId, 2026, 15, 12, "snake", "pre_draft", null,
                "{\"1\": " + managerId + "}");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id = ?", leagueId); // cascades draft, draft_pick
        jdbc.update("delete from manager where id = ?", managerId);
    }

    @Test
    void unsetOwnerConfigYieldsNullMySlotAndTheResponseActuallySerializes() throws Exception {
        ResponseEntity<?> response = controller.seats(sleeperDraftId);

        assertEquals(200, response.getStatusCode().value());
        Object body = response.getBody();
        assertNotNull(body);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) body;
        assertTrue(map.containsKey("mySlot"), "mySlot key must be present even when null");
        assertNull(map.get("mySlot"), "no owner configured -- mySlot must be null, not throw");

        // The regression itself: Map.of(...) throws at construction time, before this
        // point is ever reached, so getting here at all is already meaningful -- but
        // also force real Jackson serialization to prove the whole response is sound.
        String json = objectMapper.writeValueAsString(body);
        assertTrue(json.contains("\"mySlot\":null"), "serialized response must carry mySlot as JSON null: " + json);
    }

    /**
     * seats() serialized status as String.valueOf(draft.status()), which turns a
     * null column into the literal four-character string "null" -- valid JSON, and
     * indistinguishable from a real status to the frontend rendering it (which then
     * calls .replace() on it and paints a `status-null` chip). claude/lessons.md
     * #12, in the one place that fix had not landed. The response map is already a
     * LinkedHashMap, so the workaround was not buying anything either.
     */
    @Test
    void aNullDraftStatusSerializesAsJsonNullNotTheStringNull() throws Exception {
        jdbc.update("update draft set status = null where sleeper_draft_id = ?", sleeperDraftId);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.seats(sleeperDraftId).getBody();
        assertNotNull(body);
        assertNull(body.get("status"), "a null status column must stay null, not become \"null\"");

        String json = objectMapper.writeValueAsString(body);
        assertTrue(json.contains("\"status\":null"), "serialized response must carry status as JSON null: " + json);
        assertFalse(json.contains("\"status\":\"null\""), "the literal string \"null\" leaked into the response");
    }
}
