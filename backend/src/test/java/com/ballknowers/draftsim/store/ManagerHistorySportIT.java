package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/006-deeper-history-both-sports T011 (US1, SC-001). Pins the fix for
 * the defect baseline.md's T002 recorded live: {@code GET
 * /api/managers/7/history} returned six season rows across three leagues and
 * two sports with no {@code sport} field on any of them, so
 * {@code ManagerHistory.tsx} summed NBA wins into an NFL header record.
 *
 * <p>Builds one manager with a roster-season in each sport (mirroring
 * popsharky, who really does have both) and asserts
 * {@link RosterSeasonRepository#forManager} tells them apart. Modeled on
 * {@link LeagueHistoryContaminationIT}'s Spring/Postgres setup.
 */
@SpringBootTest
class ManagerHistorySportIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping manager-history sport test");
    }

    @Autowired private RosterSeasonRepository rosterSeasons;
    @Autowired private JdbcTemplate jdbc;

    private long nflLeagueId;
    private long nbaLeagueId;
    private long managerId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id in (?, ?)",
                "it-league-history-sport-nfl", "it-league-history-sport-nba");
        jdbc.update("delete from manager where sleeper_user_id = ?", "it-user-history-sport");

        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-history-sport", "IT Two-Sport Manager");

        // Both sides of the same manager's career -- the exact shape eleven
        // of the twelve Ball Knowers managers are in (baseline.md T002).
        nflLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters, status) values (?, ?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2025, "it-league-history-sport-nfl", "IT Football League", 12, "complete");
        nbaLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters, status) values (?, ?, ?, ?, ?, ?) returning id",
                Long.class, "nba", 2025, "it-league-history-sport-nba", "IT Basketball League", 12, "complete");

        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for, final_placement)
                values (?, ?, 1, 10, 4, 2042.84, 1)
                """, nflLeagueId, managerId);
        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for, final_placement)
                values (?, ?, 2, 17, 4, 5146.00, null)
                """, nbaLeagueId, managerId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id in (?, ?)", nflLeagueId, nbaLeagueId);
        jdbc.update("delete from manager where id = ?", managerId);
    }

    @Test
    void everyRowCarriesItsSport() {
        List<RosterSeasonRepository.StandingRow> rows = rosterSeasons.forManager(managerId);

        assertEquals(2, rows.size(), "one roster-season per sport, as seeded");
        for (RosterSeasonRepository.StandingRow row : rows) {
            assertNotNull(row.sport(), "every row from forManager must carry a sport -- baseline.md T002");
        }
    }

    @Test
    void aTwoSportManagerGetsAtLeastOneRowOfEach() {
        List<RosterSeasonRepository.StandingRow> rows = rosterSeasons.forManager(managerId);

        assertTrue(rows.stream().anyMatch(r -> r.sport() == com.ballknowers.draftsim.domain.Sport.NFL),
                "at least one nfl row expected");
        assertTrue(rows.stream().anyMatch(r -> r.sport() == com.ballknowers.draftsim.domain.Sport.NBA),
                "at least one nba row expected");
    }

    @Test
    void leagueNameAndCompleteAreAlsoCarried() {
        List<RosterSeasonRepository.StandingRow> rows = rosterSeasons.forManager(managerId);

        RosterSeasonRepository.StandingRow nfl = rows.stream()
                .filter(r -> r.sport() == com.ballknowers.draftsim.domain.Sport.NFL)
                .findFirst().orElseThrow();
        assertEquals("IT Football League", nfl.leagueName());
        assertTrue(nfl.complete(), "seeded with status = complete");
    }
}
