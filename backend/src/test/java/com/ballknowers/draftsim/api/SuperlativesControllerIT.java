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
     * Binds a request so {@code LeagueMembership} can read one; it carries the admin token,
     * which conduct-list writes no longer need (claude/audit-2026-09-28/04, amended
     * 2026-10-05). The tests at the bottom drop it via {@link com.ballknowers.draftsim.TestAdmin#withToken}
     * to show the writes work without it.
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
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "reason", 1, null), OUTSIDER_USER)
                .getStatusCode().value());
        assertEquals(404, controller.deleteConductEntry(LEAGUE_SLEEPER_ID, 1L, OUTSIDER_USER).getStatusCode().value());

        // An unknown league id entirely -- same 404, existence not leaked.
        assertEquals(404, controller.conductList("it-no-such-league", OUTSIDER_USER).getStatusCode().value());
    }

    @Test
    void nonCommissionerPostIs403() {
        ResponseEntity<?> response = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "bad behavior", 1, null), MEMBER_USER);

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
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, tooLong, 1, null), COMMISSIONER_USER);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void deleteOfAnotherLeaguesEntryIs404() {
        ResponseEntity<?> saved = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "conduct reason", 3, null), COMMISSIONER_USER);
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
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "  conduct reason  ", 4, null),
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

    // --- no admin token (claude/audit-2026-09-28/04, amended 2026-10-05) ------------------------

    /**
     * Commissioner writes are an honour system: the commissioner identity is a header anyone
     * can copy from Sleeper's public league-users list, and the admin token is the operator's
     * alone. The real commissioner with no token can add and remove an entry.
     */
    @Test
    void theRealCommissionerWithoutTheAdminTokenCanWriteBoth() {
        try (var noToken = com.ballknowers.draftsim.TestAdmin.withToken(null)) {
            ResponseEntity<?> post = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                    new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "no token", 1, null), COMMISSIONER_USER);
            assertEquals(200, post.getStatusCode().value());
            @SuppressWarnings("unchecked")
            long entryId = ((Number) ((Map<String, Object>) post.getBody()).get("id")).longValue();

            assertEquals(204, controller.deleteConductEntry(LEAGUE_SLEEPER_ID, entryId, COMMISSIONER_USER)
                    .getStatusCode().value());
        }
    }

    /** Without a token, a member who is not the commissioner still gets the commissioner refusal. */
    @Test
    void aNonCommissionerWithoutTheAdminTokenIsRefusedAsNotTheCommissioner() {
        try (var noToken = com.ballknowers.draftsim.TestAdmin.withToken(null)) {
            ResponseEntity<?> response = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                    new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "x", 1, null), MEMBER_USER);
            assertEquals(403, response.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertNotEquals(AdminGateInterceptor.REFUSAL_CODE, body.get("code"));
        }
    }

    /** Reads stay open to any member. */
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

    // --- spec 040 (US2): through-week, PUT, charges, candidates ---------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyOf(ResponseEntity<?> r) {
        return (Map<String, Object>) r.getBody();
    }

    private long saveEntry(String reason, int from, Integer through) {
        ResponseEntity<?> saved = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, reason, from, through), COMMISSIONER_USER);
        assertEquals(200, saved.getStatusCode().value());
        return ((Number) bodyOf(saved).get("id")).longValue();
    }

    @Test
    void putEditsInPlaceAndKeepsTheOriginalAdder() {
        long id = saveEntry("first", 2, null);

        ResponseEntity<?> put = controller.updateConductEntry(LEAGUE_SLEEPER_ID, id,
                new SuperlativesController.ConductEntryUpdate("  second  ", 3, 5), COMMISSIONER_USER);

        assertEquals(200, put.getStatusCode().value());
        Map<String, Object> b = bodyOf(put);
        assertEquals(id, ((Number) b.get("id")).longValue(), "same row, not a new one");
        assertEquals("second", b.get("reason"));
        assertEquals(3, b.get("appliesFromWeek"));
        assertEquals(5, b.get("appliesThroughWeek"));
        assertEquals("IT Commissioner", b.get("addedBy"));
        assertFalse(b.containsKey("charges"), "PUT returns the plain row; charges are GET-only");
        assertEquals(1L, jdbc.queryForObject("select count(*) from league_conduct_entry where league_id = ?", Long.class, leagueId));
    }

    @Test
    void putWithThroughBeforeFromIs400NotA500() {
        long id = saveEntry("r", 4, null);
        ResponseEntity<?> put = controller.updateConductEntry(LEAGUE_SLEEPER_ID, id,
                new SuperlativesController.ConductEntryUpdate("r", 4, 3), COMMISSIONER_USER);
        assertEquals(400, put.getStatusCode().value());

        ResponseEntity<?> post = controller.saveConductEntry(LEAGUE_SLEEPER_ID,
                new SuperlativesController.ConductEntryRequest(PLAYER_SLEEPER_ID, "r", 4, 3), COMMISSIONER_USER);
        assertEquals(400, post.getStatusCode().value(), "POST validates the same way");
    }

    @Test
    void putByANonCommissionerIs403() {
        long id = saveEntry("r", 1, null);
        ResponseEntity<?> put = controller.updateConductEntry(LEAGUE_SLEEPER_ID, id,
                new SuperlativesController.ConductEntryUpdate("hijack", 1, null), MEMBER_USER);
        assertEquals(403, put.getStatusCode().value());
        assertEquals("r", jdbc.queryForObject("select reason from league_conduct_entry where id = ?", String.class, id));
    }

    @Test
    void putWithAnotherLeaguesEntryIdIs404() {
        long id = saveEntry("r", 1, null);
        leagueMembers.upsert(otherLeagueId, commissionerManagerId, true, "IT Commissioner's Other Team");
        ResponseEntity<?> put = controller.updateConductEntry(OTHER_LEAGUE_SLEEPER_ID, id,
                new SuperlativesController.ConductEntryUpdate("x", 1, null), COMMISSIONER_USER);
        assertEquals(404, put.getStatusCode().value());
        assertEquals("r", jdbc.queryForObject("select reason from league_conduct_entry where id = ?", String.class, id));
    }

    @Test
    void postReAddOfAListedPlayerOverwritesTheThroughWeek() {
        long id = saveEntry("r", 2, 6);
        long again = saveEntry("r", 2, null);
        assertEquals(id, again, "upsert on (league, player)");
        assertNull(jdbc.queryForObject("select applies_through_week from league_conduct_entry where id = ?",
                Integer.class, id), "re-adding without a through-week clears it (plan-review F10)");
    }

    /**
     * roster_season.players is null here (never fetched), so the fantasy team has to come from the latest scored
     * week's players_points (plan-review F4b); and a ranged entry charges only its weeks.
     */
    @Test
    @SuppressWarnings("unchecked")
    void getReturnsCandidatesFromWeekPointsAndChargesForARangedEntry() {
        long secondPlayerRow = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{WR}') returning id",
                Long.class, "it-player-conduct-second", "IT Second Player");
        try {
            jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 1)",
                    leagueId, commissionerManagerId);
            for (int week = 1; week <= 4; week++) {
                String pp = week == 4
                        ? "{\"" + PLAYER_SLEEPER_ID + "\": 10.0, \"it-player-conduct-second\": 4.0}"
                        : "{\"" + PLAYER_SLEEPER_ID + "\": 10.0}";
                jdbc.update("insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)"
                        + " values (?, 2026, ?, 1, 10, ?::jsonb)", leagueId, week, pp);
            }
            saveEntry("range", 2, 3);

            Map<String, Object> list = bodyOf(controller.conductList(LEAGUE_SLEEPER_ID, COMMISSIONER_USER));

            assertEquals(2026, list.get("season"));
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) list.get("candidates");
            assertEquals(2, candidates.size());
            for (Map<String, Object> c : candidates) {
                Map<String, Object> team = (Map<String, Object>) c.get("fantasyTeam");
                assertNotNull(team, c.get("playerName") + " is on roster 1 in the latest week");
                assertEquals(1, team.get("rosterId"));
            }
            Map<String, Object> entry = ((List<Map<String, Object>>) list.get("entries")).get(0);
            assertEquals(3, entry.get("appliesThroughWeek"));
            assertEquals(2, entry.get("playerWeeks"), "weeks 2 and 3 only");
            List<Map<String, Object>> charges = (List<Map<String, Object>>) entry.get("charges");
            assertEquals(1, charges.size());
            assertEquals(List.of(2, 3), charges.get(0).get("weeks"));
            assertEquals(List.of(), entry.get("alsoFlaggedBySleeperWeeks"));
        } finally {
            jdbc.update("delete from player where id = ?", secondPlayerRow);
        }
    }

    /** A brand-new season: no scored week, no roster fetch -- the picker's candidates are empty, not an error. */
    @Test
    void getOnALeagueWithNoRosterDataHasNoCandidates() {
        Map<String, Object> list = bodyOf(controller.conductList(LEAGUE_SLEEPER_ID, COMMISSIONER_USER));
        assertEquals(List.of(), list.get("candidates"));
    }
}
