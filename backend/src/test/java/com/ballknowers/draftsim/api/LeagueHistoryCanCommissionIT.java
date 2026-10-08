package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.store.LeagueMembership;
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
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 013 T032: history's {@code canCommission} and per-row {@code isMe}, against
 * real Postgres. {@code isMe} is by manager id, never username.
 */
@SpringBootTest(properties = "draftsim.owner.sleeper-user-id=it-user-hist-owner")
class LeagueHistoryCanCommissionIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL);
    }

    @Autowired private LeagueHistoryController controller;
    @Autowired private LeagueMembership membership;
    @Autowired private JdbcTemplate jdbc;

    private static final String SLEEPER_ID = "it-league-hist-cc";

    @BeforeEach
    void setUp() {
        tearDown();
        long commish = manager("it-user-hist-commish", "Commish");
        long member = manager("it-user-hist-member", "Member");
        long owner = manager("it-user-hist-owner", "Owner");
        long leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nfl', 2026, ?, 'Hist CC', 3) returning id",
                Long.class, SLEEPER_ID);
        int roster = 1;
        for (long m : new long[] {commish, member, owner}) {
            jdbc.update("insert into roster_season (league_id, manager_id, roster_id, wins, losses, ties) values (?, ?, ?, 1, 1, 0)",
                    leagueId, m, roster++);
            jdbc.update("insert into league_member (league_id, manager_id, is_commissioner) values (?, ?, ?)",
                    leagueId, m, m == commish);
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", SLEEPER_ID);
        jdbc.update("delete from manager where sleeper_user_id like 'it-user-hist-%'");
    }

    private long manager(String sleeperUserId, String name) {
        return jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, name);
    }

    /** The body as JSON, so this holds whether the controller builds a map or a response
     *  record (spec 021 moved off the cast to Map). */
    private JsonNode body(String user) {
        ResponseEntity<?> r = controller.history(SLEEPER_ID, user);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "status for " + user);
        return GoldenJson.MAPPER.valueToTree(r.getBody());
    }

    private List<String> meManagers(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : body.get("seasons").get(0).get("standings")) {
            if (row.get("isMe").asBoolean()) out.add(row.get("manager").asText());
        }
        return out;
    }

    @Test
    void canCommissionIsTrueForSleeperCommissionerAndConfiguredOwnerOnly() {
        assertTrue(body("it-user-hist-commish").get("canCommission").booleanValue());
        assertTrue(body("it-user-hist-owner").get("canCommission").booleanValue());
        assertFalse(body("it-user-hist-member").get("canCommission").booleanValue());
    }

    @Test
    void noHeaderSeesNothingAndCannotCommission() {
        // The scoping gate answers 404 before any body exists; the rule underneath is false too.
        assertEquals(404, controller.history(SLEEPER_ID, null).getStatusCode().value());
        assertFalse(membership.canCommission(1L, null));
    }

    @Test
    void isMeIsTrueOnExactlyTheCallersRow() {
        assertEquals(List.of("Member"), meManagers(body("it-user-hist-member")));
        assertEquals(List.of("Commish"), meManagers(body("it-user-hist-commish")));
    }
}
