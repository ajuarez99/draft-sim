package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.PlayoffOddsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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
 * claude/audit-2026-09-28/10: a playoff-odds snapshot for a week past the latest FINAL one (an
 * unplayed or in-progress week, e.g. written by the old Recompute button that passed the open
 * ballot week) must not shadow a valid snapshot below it. Real Postgres, because the selection
 * is "highest week" in SQL and the bound is a join of three tables. Seeds and deletes its own
 * league (cascade); the live league's bad row is left alone.
 */
@SpringBootTest
class PlayoffOddsSnapshotSelectionIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final long LEAGUE = 999_102L;
    private static final String SLEEPER = "fixture-" + LEAGUE;

    @Autowired private PlayoffOddsService service;
    @Autowired private PlayoffOddsRepository odds;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres at " + JDBC_URL);
    }

    /** Weeks 1-3 stored; 1 and 2 final, 3 in progress. Odds snapshots at week 2 (valid) and 4 (unplayed). */
    private void seed() {
        jdbc.update("""
                insert into league (id, sport, season, sleeper_id, name, total_rosters, settings_json)
                values (?, 'nba', 2099, ?, 'odds selection fixture', 2,
                        '{"playoff_teams": 6, "playoff_week_start": 15}'::jsonb)
                on conflict (id) do nothing
                """, LEAGUE, SLEEPER);
        for (int w = 1; w <= 3; w++) {
            jdbc.update("insert into roster_week_points (league_id, season, week, roster_id, starters_points) values (?, 2099, ?, 1, 100)",
                    LEAGUE, w);
            jdbc.update("insert into league_week_fetch (league_id, kind, week, fetched_at, final) values (?, 'POINTS', ?, now(), ?)",
                    LEAGUE, w, w < 3);
        }
        odds.save(LEAGUE, 2099, 2, 10_000, "test", List.of(new PlayoffOddsRepository.Entry(1, 50.0, null, 10.0, 6.0, 100.0)));
        odds.save(LEAGUE, 2099, 4, 10_000, "test", List.of(new PlayoffOddsRepository.Entry(1, 99.0, null, 10.0, 6.0, 100.0)));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from league where id = ?", LEAGUE);
    }

    @Test
    void theForecastShowsTheSnapshotAtOrBelowTheLatestFinalWeekNotTheHigherOne() {
        seed();

        var f = service.forecast(SLEEPER).orElseThrow();

        assertTrue(f.available());
        assertEquals(2, f.week(), "week 4 is unplayed and must not shadow week 2");
        assertEquals(50.0, f.teams().get(0).playoffOdds());
        assertEquals(3, f.latestScoredWeek());
        assertEquals(2, f.latestFinalWeek());
    }

    @Test
    void thePowerRankingsSummaryAndPillsIgnoreTheSameRow() {
        seed();

        assertEquals(2, service.summary(LEAGUE, 2099).orElseThrow().week());
        var byWeek = service.madePctByWeek(LEAGUE, 2099);
        assertTrue(byWeek.containsKey(2));
        assertFalse(byWeek.containsKey(4), "no Makes-playoffs pill for a week that has not been played");
        assertTrue(service.madePctByRoster(LEAGUE, 2099, 4).isEmpty());
        assertEquals(50.0, service.madePctByRoster(LEAGUE, 2099, 2).get(1));
    }

    /** The row is ignored on read, not deleted: reversible, and no migration. */
    @Test
    void theOutOfRangeRowIsStillInTheTable() {
        seed();
        service.forecast(SLEEPER);
        assertTrue(odds.forWeek(LEAGUE, 2099, 4).isPresent());
    }

    /** No fetch rows at all: every stored week counts as final, so the week-3 snapshot is valid. */
    @Test
    void withNoFetchRowsEveryStoredWeekIsFinalAndTheHighestValidSnapshotWins() {
        seed();
        jdbc.update("delete from league_week_fetch where league_id = ?", LEAGUE);
        odds.save(LEAGUE, 2099, 3, 10_000, "test", List.of(new PlayoffOddsRepository.Entry(1, 70.0, null, 10.0, 6.0, 100.0)));

        var f = service.forecast(SLEEPER).orElseThrow();

        assertEquals(3, f.week(), "week 3 is final here; week 4 is still beyond anything stored");
        assertEquals(70.0, f.teams().get(0).playoffOdds());
    }

    @Test
    void aLoadedCompleteSeasonTreatsItsLastStoredWeekAsFinal() {
        seed();
        jdbc.update("insert into league_refresh (league_id, last_success_at, loaded_complete) values (?, now(), true)", LEAGUE);
        odds.save(LEAGUE, 2099, 3, 10_000, "test", List.of(new PlayoffOddsRepository.Entry(1, 70.0, null, 10.0, 6.0, 100.0)));

        assertEquals(3, service.forecast(SLEEPER).orElseThrow().week());
    }
}
