package com.ballknowers.draftsim.api;

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
 * Real-Postgres coverage for LeagueController.seats()'s mySlot field with
 * APP_OWNER_SLEEPER_USER_ID configured -- same convention as
 * DraftRepositoryAllWithLeagueIT/LeagueControllerSeatsUnsetOwnerIT: reuses the
 * real Spring context + Flyway-migrated schema against application.yml's
 * local-dev default (localhost:5433/draftsim), gated to SKIP (not fail) when
 * that Postgres isn't reachable.
 *
 * Overrides the final bound property (draftsim.owner.sleeper-user-id) directly
 * rather than the APP_OWNER_SLEEPER_USER_ID env var indirection -- same result,
 * simpler in a test context. Covers the other two of the three mySlot states
 * (matched / no match in this league); the fourth case -- unset config actually
 * serializing -- lives in LeagueControllerSeatsUnsetOwnerIT since that needs the
 * default (unconfigured) context, not this one.
 */
@SpringBootTest
@TestPropertySource(properties = "draftsim.owner.sleeper-user-id=it-owner-seats-configured")
class LeagueControllerSeatsOwnerConfiguredIT {

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

    private long leagueId;
    private long ownerManagerId;
    private long otherManagerId;
    private String draftWithOwnerId;
    private String draftWithoutOwnerId;

    @BeforeEach
    void setUp() {
        // Fixture rows scoped under distinct "it-" sleeper ids; wiped up front in case a
        // previous run was interrupted before tearDown ran.
        jdbc.update("delete from league where sleeper_id = ?", "it-league-seats-owner-configured");
        jdbc.update("delete from manager where sleeper_user_id in (?, ?)",
                "it-owner-seats-configured", "it-other-seats-configured");

        ownerManagerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-owner-seats-configured", "IT Owner");
        otherManagerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-other-seats-configured", "IT Other Manager");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-seats-owner-configured", "IT League", 12);

        // The owner sits in slot 7 of this draft.
        draftWithOwnerId = "it-draft-seats-owner-present";
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams,
                                   draft_type, status, start_time, slot_to_manager, pick_timer_seconds)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                leagueId, draftWithOwnerId, 2026, 15, 12, "snake", "pre_draft", null,
                "{\"1\": " + otherManagerId + ", \"7\": " + ownerManagerId + "}", 120);

        // A separate league/draft the configured owner isn't actually a manager in.
        draftWithoutOwnerId = "it-draft-seats-owner-absent";
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams,
                                   draft_type, status, start_time, slot_to_manager)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                leagueId, draftWithoutOwnerId, 2026, 15, 12, "snake", "pre_draft", null,
                "{\"1\": " + otherManagerId + "}");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id = ?", leagueId); // cascades draft, draft_pick
        jdbc.update("delete from manager where id in (?, ?)", ownerManagerId, otherManagerId);
    }

    // Both tests below call seats() with NO X-Sleeper-User, because the configured owner is only
    // consulted as the seat-preselect fallback when the header is blank. A blank caller is no longer
    // let through (claude/audit-2026-09-28/01), so they run as the operator (admin token) -- the only
    // caller left for whom "no header" reaches the fallback at all. A signed-in caller never falls
    // back to the owner: OwnerSlot.resolve prefers the header.

    @Test
    void configuredOwnerPresentInLeagueYieldsTheirSlot() {
        ResponseEntity<?> response;
        try (var admin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            response = controller.seats(draftWithOwnerId, null);
        }

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) response.getBody();
        assertNotNull(map);
        assertEquals(7, map.get("mySlot"), "configured owner sits in slot 7 of this draft");
        assertEquals("it-league-seats-owner-configured", map.get("sleeperLeagueId"),
                "spec 023: the seats payload names the league the draft room links into");
        assertEquals("snake", map.get("draftType"), "spec 024: draft format rides on the seats payload");
        assertEquals(120, map.get("pickTimerSeconds"), "spec 024: stored pick_timer_seconds is returned");
    }

    @Test
    void configuredOwnerAbsentFromLeagueYieldsNullMySlot() {
        ResponseEntity<?> response;
        try (var admin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            response = controller.seats(draftWithoutOwnerId, null);
        }

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) response.getBody();
        assertNotNull(map);
        assertNull(map.get("mySlot"), "configured owner isn't a manager in this league -- must be null, not throw");
        assertTrue(map.containsKey("pickTimerSeconds"), "key present even when null");
        assertNull(map.get("pickTimerSeconds"), "no timer stored -> null, never a default number");
    }
}
