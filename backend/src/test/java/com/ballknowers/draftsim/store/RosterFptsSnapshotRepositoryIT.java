package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * claude/official-week-scores.md: V44 and its writer against REAL Postgres (numeric binds and the upsert are
 * the class a mock can't catch -- lessons #2/#3). SKIPS when Postgres is unreachable; read the skip count.
 */
@SpringBootTest
class RosterFptsSnapshotRepositoryIT {

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping fpts snapshot IT");
    }

    @Autowired private RosterFptsSnapshotRepository snapshots;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void seed() {
        clean();
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nba', 2026, 'it055-fpts', 'IT 055', 2) returning id",
                Long.class);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from league where sleeper_id = 'it055-fpts'");
    }

    private static RosterFptsSnapshotRepository.Snapshot snap(int roster, String fpts, String against) {
        return new RosterFptsSnapshotRepository.Snapshot(roster, new BigDecimal(fpts),
                against == null ? null : new BigDecimal(against));
    }

    @Test
    void aLaterReadInTheSameLegReplacesTheEarlierOneAndOtherLegsAreKept() {
        snapshots.upsertAll(leagueId, 1, List.of(snap(1, "240.50", "200.00"), snap(2, "200.00", null)));
        snapshots.upsertAll(leagueId, 2, List.of(snap(1, "400.00", "380.00"), snap(2, "380.00", "400.00")));
        snapshots.upsertAll(leagueId, 2, List.of(snap(1, "529.50", "450.50"), snap(2, "450.50", "529.50")));

        Map<Integer, Map<Integer, BigDecimal>> byWeek = snapshots.byWeek(leagueId);
        assertEquals(new BigDecimal("240.50"), byWeek.get(1).get(1));
        assertEquals(new BigDecimal("529.50"), byWeek.get(2).get(1));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from roster_fpts_snapshot where league_id = ? and through_week = 2", Integer.class, leagueId));
        assertNull(jdbc.queryForObject(
                "select fpts_against from roster_fpts_snapshot where league_id = ? and roster_id = 2 and through_week = 1",
                BigDecimal.class, leagueId));

        var official = RosterFptsSnapshotRepository.officialWeekScores(byWeek, java.util.Set.of(1, 2));
        assertEquals(new BigDecimal("289.00"), official.get(2).get(1));
        assertEquals(new BigDecimal("250.50"), official.get(2).get(2));
    }
}
