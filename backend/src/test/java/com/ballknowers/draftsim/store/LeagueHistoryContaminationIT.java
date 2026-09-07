package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ProfileService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * claude/league-suite.md Phase A acceptance criterion 2 ("ProfileService.fit()
 * output is byte-identical before and after the history ingest runs. The
 * contamination guard is a test, not a code comment") -- same convention as
 * {@link MockDraftContaminationIT}, extended to the new roster_season /
 * roster_week_points tables. Uses a manager id that is BOTH a real manager
 * with real completed-draft history AND the manager_id recorded on a
 * roster_season row, the scenario that would expose a leak.
 */
@SpringBootTest
class LeagueHistoryContaminationIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping league-history contamination test");
    }

    @Autowired private DraftRepository drafts;
    @Autowired private ProfileService profiles;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long historyLeagueId;
    private long managerId;
    private long realDraftId;
    private long playerId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id in (?, ?)",
                "it-league-history-contamination", "it-league-history-contamination-2");
        jdbc.update("delete from manager where sleeper_user_id = ?", "it-user-history-contamination");
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id = ?", "it-player-history-contamination");

        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-history-contamination", "IT Manager");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-history-contamination", "IT League", 8);
        historyLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2025, "it-league-history-contamination-2", "IT History League", 8);
        playerId = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{RB}') returning id",
                Long.class, "it-player-history-contamination", "IT Player");

        realDraftId = drafts.upsert(leagueId, "it-draft-history-contamination", 2026, 15, 8,
                "snake", "complete", null, "{}");
        drafts.upsertPicks(realDraftId, java.util.List.of(
                new DraftRepository.PickRow(realDraftId, 1, 1, 1, managerId, playerId, 1.0)));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id in (?, ?)", leagueId, historyLeagueId);
        jdbc.update("delete from manager where id = ?", managerId);
        jdbc.update("delete from player where id = ?", playerId);
    }

    @Test
    void writingRosterSeasonAndWeekPointsForThisManagerDoesNotChangeFittedProfiles() {
        ProfileService.Fit before = profiles.fit(Sport.NFL);

        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for, final_placement)
                values (?, ?, 3, 10, 4, 1500.42, 1)
                """, historyLeagueId, managerId);
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, 2025, 1, 3, 123.45, '{}'::jsonb)
                """, historyLeagueId);

        ProfileService.Fit after = profiles.fit(Sport.NFL);

        assertEquals(before.scoreablePicks(), after.scoreablePicks(),
                "roster_season/roster_week_points rows must not be counted as picks");
        assertEquals(before.profiles().get(managerId).picksScored(),
                after.profiles().get(managerId).picksScored(),
                "this manager's fitted picksScored must be unaffected by their roster_season row");
        assertEquals(before.profiles().get(managerId).reachBias(),
                after.profiles().get(managerId).reachBias(),
                "this manager's fitted reachBias must be unaffected");

        long leaked = drafts.allCompletedPicks().stream()
                .filter(p -> p.playerId() != null && p.playerId() == playerId)
                .count();
        assertEquals(1, leaked, "still exactly the one real draft_pick row, nothing from roster_season");
    }
}
