package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 T016 / T024 (plan amendment 8 / R3): the full-standings payload, asserted on the CONTROLLER's
 * response body, not the service result -- the controller copies fields by hand, so a field that never
 * reaches the wire is invisible to a service-level test (R1).
 *
 * <p>Seeds its own small synthetic NFL season rather than leaning on local data, which has no orphaned
 * roster (R2): four rosters, roster 4 orphaned (null manager), three regular-season weeks of scores and
 * pairings, one tied top score and one 0-margin game. Same conventions as {@code SuperlativesControllerIT}:
 * the real Spring context against application.yml's local-dev Postgres, SKIPPED (not failed) when that
 * isn't reachable, unique {@code sleeper_id}s, and cleanup that only touches rows this test created.
 */
@SpringBootTest
class SuperlativesStandingsIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE_SLEEPER_ID = "it-league-standings-010";
    private static final String[] MANAGER_USERS = {
            "it-user-standings-010-a", "it-user-standings-010-b", "it-user-standings-010-c"};
    private static final String PLAYER_X = "it-player-standings-010-x";
    private static final String PLAYER_Y = "it-player-standings-010-y";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping SuperlativesStandings IT");
    }

    @Autowired private SuperlativesController controller;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void seed() {
        TestAdmin.asAdmin();
        cleanUp(); // a previous aborted run's leftovers, by OUR unique ids only

        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, settings_json, roster_positions)
                values ('nfl', 2026, ?, 'IT Standings League', 4, '{"playoff_week_start": 15}'::jsonb,
                        '{QB,RB,RB,WR,WR,TE,FLEX,BN,BN}')
                returning id
                """, Long.class, LEAGUE_SLEEPER_ID);

        // Rosters 1-3 have managers; roster 4 is ORPHANED (manager_id null) -- the case local data can't produce.
        for (int i = 0; i < 3; i++) {
            long managerId = jdbc.queryForObject(
                    "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                    Long.class, MANAGER_USERS[i], "IT Standings " + (i + 1));
            jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, ?)",
                    leagueId, managerId, i + 1);
        }
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, null, 4)", leagueId);

        // week -> {roster1, roster2, roster3, roster4} scores. Pairings: wk1 1v2 3v4, wk2 1v3 2v4, wk3 1v4 2v3.
        //   wk1: 150.00 150.00  90.00  80.00   -> rosters 1 and 2 TIE for the top score, and their game is a 0-margin game
        //   wk2: 100.50 110.00 100.00  60.00
        //   wk3: 120.00  95.00 105.00 118.00
        double[][] scores = {
                {150.00, 150.00, 90.00, 80.00},
                {100.50, 110.00, 100.00, 60.00},
                {120.00, 95.00, 105.00, 118.00}};
        int[][][] pairings = {
                {{1, 2}, {3, 4}},
                {{1, 3}, {2, 4}},
                {{1, 4}, {2, 3}}};
        for (int w = 0; w < 3; w++) {
            for (int r = 0; r < 4; r++) {
                jdbc.update("""
                        insert into roster_week_points (league_id, season, week, roster_id, starters_points)
                        values (?, 2026, ?, ?, ?)
                        """, leagueId, w + 1, r + 1, scores[w][r]);
            }
            int matchupId = 1;
            for (int[] pair : pairings[w]) {
                for (int roster : pair) {
                    jdbc.update("""
                            insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                            values (?, 2026, ?, ?, ?)
                            """, leagueId, w + 1, roster, matchupId);
                }
                matchupId++;
            }
        }
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        RequestContextHolder.resetRequestAttributes();
    }

    /** Only rows this IT created: the league (cascades roster_season / week points / matchups / transactions), its managers, its players. */
    private void cleanUp() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE_SLEEPER_ID);
        for (String u : MANAGER_USERS) jdbc.update("delete from manager where sleeper_user_id = ?", u);
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id in (?, ?)", PLAYER_X, PLAYER_Y);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() {
        ResponseEntity<?> res = controller.superlatives(LEAGUE_SLEEPER_ID, null);
        assertEquals(200, res.getStatusCode().value());
        return (Map<String, Object>) res.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> team(Map<String, Object> standing) {
        return (Map<String, Object>) standing.get("team");
    }

    private static Map<String, Object> kind(Map<String, Object> body, String name) {
        return list(body, "superlatives").stream().filter(s -> name.equals(s.get("kind"))).findFirst().orElseThrow();
    }

    private static Set<Integer> rosterIdsOf(List<Map<String, Object>> rows, boolean standings) {
        Set<Integer> out = new HashSet<>();
        for (Map<String, Object> r : rows) out.add((Integer) (standings ? team(r) : r).get("rosterId"));
        return out;
    }

    private static List<Integer> ranks(List<Map<String, Object>> standings) {
        return standings.stream().map(s -> (Integer) s.get("rank")).toList();
    }

    @Test
    void everyAvailableKindWithHoldersCarriesAllFourRostersAndRankOneMatchesTheCard() {
        Map<String, Object> body = body();
        assertEquals(Boolean.TRUE, body.get("available"), "the seeded season must resolve and be scored");

        int checked = 0;
        for (Map<String, Object> s : list(body, "superlatives")) {
            String kind = (String) s.get("kind");
            boolean hasHolders = !list(s, "holders").isEmpty();
            if (!Boolean.TRUE.equals(s.get("available")) || !hasHolders) continue;
            if ("JABARI_SMITH_JR".equals(kind)) continue; // player-headed; covered by its own test
            checked++;

            List<Map<String, Object>> standings = list(s, "standings");
            assertEquals(4, standings.size(), kind + ": one row per roster_season row");
            Set<Integer> ids = rosterIdsOf(standings, true);
            assertEquals(Set.of(1, 2, 3, 4), ids, kind + ": every roster exactly once");

            Map<String, Object> orphan = standings.stream()
                    .filter(r -> Integer.valueOf(4).equals(team(r).get("rosterId"))).findFirst().orElseThrow();
            assertEquals("Roster 4", team(orphan).get("teamName"), kind + ": the orphan is named Roster N");
            assertNull(team(orphan).get("managerId"));

            for (Map<String, Object> r : standings) {
                // R1: the hand-built map must carry every field, nullable ones included, as keys.
                for (String key : List.of("rank", "team", "value", "note", "hasValue", "missingReason")) {
                    assertTrue(r.containsKey(key), kind + ": standing is missing wire field " + key);
                }
                assertEquals(r.get("rank") == null, !(Boolean) r.get("hasValue"));
                assertEquals(r.get("missingReason") != null, !(Boolean) r.get("hasValue"));
            }

            Set<Integer> rankOne = new HashSet<>();
            for (Map<String, Object> r : standings) {
                if (Integer.valueOf(1).equals(r.get("rank"))) rankOne.add((Integer) team(r).get("rosterId"));
            }
            Set<Integer> holders = rosterIdsOf(list(s, "holders"), false);
            if ("CLOSEST_GAME".equals(kind)) {
                assertTrue(rankOne.containsAll(holders), kind + ": holders must be a subset of rank 1 (plan amendment 2)");
            } else {
                assertEquals(holders, rankOne, kind + ": rank-1 rosters must equal the card's holders");
            }
        }
        assertTrue(checked >= 6, "at least the six record and close-game kinds should have been checked, was " + checked);
    }

    @Test
    void highestWeekRanksAreOneOneThreeFourAndOrderedHighToLow() {
        List<Map<String, Object>> standings = list(kind(body(), "HIGHEST_WEEK"), "standings");
        assertEquals(List.of(1, 1, 3, 4), ranks(standings));
        List<Double> values = standings.stream().map(r -> ((Number) r.get("value")).doubleValue()).toList();
        assertEquals(List.of(150.0, 150.0, 118.0, 105.0), values);
        assertEquals(Set.of(1, 2), Set.of(
                (Integer) team(standings.get(0)).get("rosterId"), (Integer) team(standings.get(1)).get("rosterId")));
    }

    @Test
    void lowestWeekGoesLowToHighAndTheOrphanHoldsTheRecord() {
        Map<String, Object> s = kind(body(), "LOWEST_WEEK");
        List<Map<String, Object>> standings = list(s, "standings");
        List<Double> values = standings.stream().map(r -> ((Number) r.get("value")).doubleValue()).toList();
        assertEquals(List.of(60.0, 90.0, 95.0, 100.5), values, "lowest week is ordered low to high");
        assertEquals(4, team(standings.get(0)).get("rosterId"), "the orphan's 60 is the season low");
        assertEquals("Roster 4", team(standings.get(0)).get("teamName"));
        assertEquals(4, list(s, "holders").get(0).get("rosterId"));
    }

    @Test
    void biggestBlowoutListsTheWinlessRosterLastWithItsReason() {
        List<Map<String, Object>> standings = list(kind(body(), "BIGGEST_BLOWOUT"), "standings");
        assertEquals(2, team(standings.get(0)).get("rosterId"));
        assertEquals(50.0, ((Number) standings.get(0).get("value")).doubleValue());
        Map<String, Object> last = standings.get(3);
        assertEquals(4, team(last).get("rosterId"));
        assertEquals(Boolean.FALSE, last.get("hasValue"));
        assertNull(last.get("rank"));
        assertNull(last.get("value"));
        assertEquals("no wins yet", last.get("missingReason"));
    }

    @Test
    void closestGameSharesRankOneBetweenTheZeroMarginPairAndNamesBothSides() {
        Map<String, Object> s = kind(body(), "CLOSEST_GAME");
        List<Map<String, Object>> standings = list(s, "standings");
        // The 0-margin week-1 game is rosters 1 and 2; only the ">=" winner (roster 1) is the card's holder.
        assertEquals(List.of(1, 1), ranks(standings).subList(0, 2));
        assertEquals(Set.of(1, 2), Set.of(
                (Integer) team(standings.get(0)).get("rosterId"), (Integer) team(standings.get(1)).get("rosterId")));
        assertEquals(Set.of(1), rosterIdsOf(list(s, "holders"), false));
        for (int i = 0; i < 2; i++) {
            assertTrue(((String) standings.get(i).get("note")).startsWith("tied with "), "0-margin game reads as a tie");
        }
    }

    @Test
    void jabariWithNoTransactionsHasNoPlayerStandingsAndNoTeamStandings() {
        Map<String, Object> s = kind(body(), "JABARI_SMITH_JR");
        assertEquals(List.of(), list(s, "playerStandings"));
        assertEquals(List.of(), list(s, "standings"));
        assertEquals(List.of(), list(s, "playerHolders"));
    }

    @Test
    void jabariPlayerStandingsRankPlayersAndRankOneEqualsThePlayerHolders() {
        jdbc.update("insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, 'IT Player X', '{RB}')", PLAYER_X);
        jdbc.update("insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, 'IT Player Y', '{WR}')", PLAYER_Y);
        // X: 3 completed WAIVER adds, by two rosters (1, 1, 2). Y: 2 adds (roster 3, roster 3).
        int n = 0;
        for (Object[] add : new Object[][]{
                {PLAYER_X, 1, 1}, {PLAYER_X, 2, 1}, {PLAYER_X, 3, 2}, {PLAYER_Y, 1, 3}, {PLAYER_Y, 2, 3}}) {
            String player = (String) add[0];
            int week = (Integer) add[1];
            int roster = (Integer) add[2];
            jdbc.update("""
                    insert into league_transaction (league_id, season, week, sleeper_transaction_id, type, status,
                                                    roster_id, adds)
                    values (?, 2026, ?, ?, 'WAIVER', 'complete', ?, jsonb_build_object(?::text, ?::int))
                    """, leagueId, week, "it-tx-standings-010-" + (n++), roster, player, roster);
        }

        Map<String, Object> s = kind(body(), "JABARI_SMITH_JR");
        List<Map<String, Object>> ps = list(s, "playerStandings");
        assertEquals(2, ps.size());
        assertEquals(List.of(1, 2), ps.stream().map(r -> (Integer) r.get("rank")).toList());
        assertEquals(PLAYER_X, ps.get(0).get("playerId"));
        assertEquals("IT Player X", ps.get(0).get("playerName"));
        assertEquals(3, ps.get(0).get("adds"));
        assertEquals(2, ps.get(0).get("distinctTeams"));
        assertEquals(PLAYER_Y, ps.get(1).get("playerId"));
        assertEquals(2, ps.get(1).get("adds"));
        for (String key : List.of("rank", "playerId", "playerName", "position", "team", "adds", "distinctTeams")) {
            assertTrue(ps.get(0).containsKey(key), "playerStanding is missing wire field " + key);
        }

        // Rank 1 is exactly the card's playerHolders.
        Set<Object> holderIds = new HashSet<>();
        for (Map<String, Object> h : list(s, "playerHolders")) holderIds.add(h.get("playerId"));
        Set<Object> rankOne = new HashSet<>();
        for (Map<String, Object> r : ps) if (Integer.valueOf(1).equals(r.get("rank"))) rankOne.add(r.get("playerId"));
        assertEquals(holderIds, rankOne);
        assertEquals(List.of(), list(s, "standings"), "Jabari ranks players, so team standings stay empty");
    }
}
