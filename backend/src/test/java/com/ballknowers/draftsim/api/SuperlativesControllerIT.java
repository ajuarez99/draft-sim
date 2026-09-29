package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.store.LeagueMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for the conduct-list endpoints
 * (specs/008-season-superlatives T056, contracts/superlatives-api.md), same
 * convention as {@code LeagueControllerSeatsOwnerConfiguredIT}: the real
 * Spring context + Flyway-migrated schema against application.yml's
 * local-dev default (localhost:5433/draftsim), gated to SKIP (not fail) when
 * that Postgres isn't reachable. Calls the controller directly rather than
 * over HTTP -- CORS preflight is a browser concern the contract itself flags
 * as a live-check item (T061), not something a JVM-side IT can exercise.
 */
@SpringBootTest
class SuperlativesControllerIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping SuperlativesController IT");
    }

    @Autowired private SuperlativesController controller;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long otherLeagueId;
    private long commissionerManagerId;
    private long memberManagerId;
    private long playerRowId;

    private static final String LEAGUE_SLEEPER_ID = "it-league-conduct-list";
    private static final String OTHER_LEAGUE_SLEEPER_ID = "it-league-conduct-list-other";
    private static final String COMMISSIONER_USER = "it-user-conduct-commissioner";
    private static final String MEMBER_USER = "it-user-conduct-member";
    private static final String OUTSIDER_USER = "it-user-conduct-outsider";
    private static final String PLAYER_SLEEPER_ID = "it-player-conduct-list";

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id in (?, ?)", LEAGUE_SLEEPER_ID, OTHER_LEAGUE_SLEEPER_ID);
        jdbc.update("delete from manager where sleeper_user_id in (?, ?, ?)",
                COMMISSIONER_USER, MEMBER_USER, OUTSIDER_USER);
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id = ?", PLAYER_SLEEPER_ID);

        commissionerManagerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, COMMISSIONER_USER, "IT Commissioner");
        memberManagerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, MEMBER_USER, "IT Member");

        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, LEAGUE_SLEEPER_ID, "IT Conduct League", 10);
        otherLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, OTHER_LEAGUE_SLEEPER_ID, "IT Other Conduct League", 10);

        leagueMembers.upsert(leagueId, commissionerManagerId, true, "IT Commissioner's Team");
        leagueMembers.upsert(leagueId, memberManagerId, false, "IT Member's Team");

        playerRowId = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{RB}') returning id",
                Long.class, PLAYER_SLEEPER_ID, "IT Conduct Player");
    }

    /**
     * Every conduct-list write now needs the admin token as well as the commissioner
     * identity (claude/audit-2026-09-28/04, option D), so the tests that exercise the
     * write path itself run inside a request that carries it. The tests at the bottom
     * that are about the token drop it via {@link com.ballknowers.draftsim.TestAdmin#withToken}.
     */
    @BeforeEach
    void bindAdminRequest() {
        com.ballknowers.draftsim.TestAdmin.asAdmin();
    }

    @AfterEach
    void unbindRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id in (?, ?)", leagueId, otherLeagueId);
        jdbc.update("delete from manager where id in (?, ?)", commissionerManagerId, memberManagerId);
        jdbc.update("delete from player where id = ?", playerRowId);
    }

    @Test
    void notVisibleIs404OnAllFourEndpoints() {
        assertEquals(404, controller.conductList(LEAGUE_SLEEPER_ID, OUTSIDER_USER).getStatusCode().value());
        assertEquals(404, controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "reason", 1), OUTSIDER_USER)
                .getStatusCode().value());
        assertEquals(404, controller.deleteConductEntry(LEAGUE_SLEEPER_ID, 1L, OUTSIDER_USER).getStatusCode().value());

        // An unknown league id entirely -- same 404, existence not leaked.
        assertEquals(404, controller.conductList("it-no-such-league", OUTSIDER_USER).getStatusCode().value());
    }

    @Test
    void nonCommissionerPostIs403() {
        ResponseEntity<?> response = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "bad behavior", 1), MEMBER_USER);

        assertEquals(403, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("message"));
        assertEquals(true, body.get("commissionerKnown"));
    }

    @Test
    void reasonOver140CharsIs400() {
        String tooLong = "x".repeat(141);
        ResponseEntity<?> response = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, tooLong, 1), COMMISSIONER_USER);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void deleteOfAnotherLeaguesEntryIs404() {
        ResponseEntity<?> saved = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "conduct reason", 3), COMMISSIONER_USER);
        assertEquals(200, saved.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> savedBody = (Map<String, Object>) saved.getBody();
        assertNotNull(savedBody);
        long entryId = ((Number) savedBody.get("id")).longValue();

        // Same commissioner, but addressing the OTHER league by its sleeper id -- the entry
        // belongs to leagueId, not otherLeagueId, so it must not be reachable from here.
        leagueMembers.upsert(otherLeagueId, commissionerManagerId, true, "IT Commissioner's Other Team");
        ResponseEntity<?> response = controller.deleteConductEntry(OTHER_LEAGUE_SLEEPER_ID, entryId, COMMISSIONER_USER);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void commissionerPostThenGetRoundTrips() {
        ResponseEntity<?> saved = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "  conduct reason  ", 4),
                COMMISSIONER_USER);
        assertEquals(200, saved.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> savedBody = (Map<String, Object>) saved.getBody();
        assertNotNull(savedBody);
        assertEquals(PLAYER_SLEEPER_ID, savedBody.get("playerId"));
        assertEquals("conduct reason", savedBody.get("reason"), "reason is trimmed");
        assertEquals(4, savedBody.get("appliesFromWeek"));
        assertEquals("IT Commissioner", savedBody.get("addedBy"));

        ResponseEntity<?> list = controller.conductList(LEAGUE_SLEEPER_ID, COMMISSIONER_USER);
        assertEquals(200, list.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> listBody = (Map<String, Object>) list.getBody();
        assertNotNull(listBody);
        assertEquals(true, listBody.get("canEdit"));
        assertEquals(true, listBody.get("commissionerKnown"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) listBody.get("entries");
        assertEquals(1, entries.size());
        assertEquals(PLAYER_SLEEPER_ID, entries.get(0).get("playerId"));
        assertEquals("IT Conduct Player", entries.get(0).get("playerName"));

        // A non-commissioner member sees the same list but canEdit is false.
        ResponseEntity<?> memberList = controller.conductList(LEAGUE_SLEEPER_ID, MEMBER_USER);
        @SuppressWarnings("unchecked")
        Map<String, Object> memberBody = (Map<String, Object>) memberList.getBody();
        assertNotNull(memberBody);
        assertEquals(false, memberBody.get("canEdit"));
    }

    // --- the admin token (claude/audit-2026-09-28/04, option D) ---------------------------------

    /**
     * The commissioner identity is a header anyone can copy out of Sleeper's public
     * league-users list (`is_owner: true`), so on its own it proves nothing. The true
     * commissioner presenting no admin token must be refused, and nothing written.
     */
    @Test
    void theRealCommissionerWithoutTheAdminTokenIsRefusedOnBothWrites() {
        try (var noToken = com.ballknowers.draftsim.TestAdmin.withToken(null)) {
            ResponseEntity<?> post = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                    new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "spoofed", 1), COMMISSIONER_USER);
            assertEquals(403, post.getStatusCode().value());
            assertAdminRequired(post);

            ResponseEntity<?> delete = controller.deleteConductEntry(LEAGUE_SLEEPER_ID, 1L, COMMISSIONER_USER);
            assertEquals(403, delete.getStatusCode().value());
            assertAdminRequired(delete);
        }
        try (var asAdmin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> list = (Map<String, Object>) controller.conductList(LEAGUE_SLEEPER_ID, COMMISSIONER_USER).getBody();
            assertEquals(0, ((List<?>) list.get("entries")).size(), "the refused POST must not have written anything");
        }
    }

    @Test
    void aWrongAdminTokenIsRefusedLikeAMissingOne() {
        try (var wrong = com.ballknowers.draftsim.TestAdmin.withToken("not-the-token")) {
            ResponseEntity<?> post = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                    new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "x", 1), COMMISSIONER_USER);
            assertEquals(403, post.getStatusCode().value());
            assertAdminRequired(post);
        }
        try (var blank = com.ballknowers.draftsim.TestAdmin.withToken("")) {
            assertEquals(403, controller.deleteConductEntry(LEAGUE_SLEEPER_ID, 1L, COMMISSIONER_USER)
                    .getStatusCode().value(), "a blank presented token must never match");
        }
    }

    /** The token alone is not enough either: defence in depth keeps the commissioner identity check. */
    @Test
    void theAdminTokenWithANonCommissionerIdentityIsStillRefused() {
        ResponseEntity<?> response = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "x", 1), MEMBER_USER);
        assertEquals(403, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotEquals(AdminGateInterceptor.REFUSAL_CODE, body.get("code"),
                "this is the not-the-commissioner refusal, not the missing-token one");
    }

    /** Reads stay open to any member: only the writes moved behind the token. */
    @Test
    void readingTheListNeedsNoAdminToken() {
        try (var noToken = com.ballknowers.draftsim.TestAdmin.withToken(null)) {
            assertEquals(200, controller.conductList(LEAGUE_SLEEPER_ID, MEMBER_USER).getStatusCode().value());
        }
    }

    @Test
    void aBlankIdentityCannotReadTheListWithoutTheAdminToken() {
        try (var noToken = com.ballknowers.draftsim.TestAdmin.withToken(null)) {
            assertEquals(404, controller.conductList(LEAGUE_SLEEPER_ID, null).getStatusCode().value());
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertAdminRequired(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(AdminGateInterceptor.REFUSAL_CODE, body.get("code"));
    }
}
