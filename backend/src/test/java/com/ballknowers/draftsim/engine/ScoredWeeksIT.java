package com.ballknowers.draftsim.engine;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * claude/audit-2026-09-28/10: "scored" means FINAL, resolved against real Postgres
 * ({@code roster_week_points}, {@code league_week_fetch}, {@code league_refresh}) because the
 * rule is a join of three tables a mock cannot mean anything about. Seeds its own league and
 * deletes it (cascade) afterwards; touches no other row.
 */
@SpringBootTest
class ScoredWeeksIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final long LEAGUE = 999_101L;

    @Autowired private ScoredWeeks scoredWeeks;
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

    private void seedLeague(int... storedWeeks) {
        jdbc.update("""
                insert into league (id, sport, season, sleeper_id, name, total_rosters)
                values (?, 'nba', 2099, ?, 'scored-weeks fixture', 2)
                on conflict (id) do nothing
                """, LEAGUE, "fixture-" + LEAGUE);
        for (int w : storedWeeks) {
            jdbc.update("insert into roster_week_points (league_id, season, week, roster_id, starters_points) values (?, 2099, ?, 1, 100)",
                    LEAGUE, w);
        }
    }

    private void fetched(int week, boolean fin) {
        jdbc.update("""
                insert into league_week_fetch (league_id, kind, week, fetched_at, final)
                values (?, 'POINTS', ?, now(), ?)
                """, LEAGUE, week, fin);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from league where id = ?", LEAGUE);
    }

    @Test
    void theLatestFinalWeekTrailsTheLatestStoredWeekWhileOneIsInProgress() {
        seedLeague(1, 2, 3);
        fetched(1, true);
        fetched(2, true);
        fetched(3, false);

        var s = scoredWeeks.of(LEAGUE);
        assertEquals(3, s.latestStored());
        assertEquals(2, s.latestFinal(), "the default week is 2, not the in-progress 3");
        assertTrue(s.isFinal(2));
        assertFalse(s.isFinal(3));
    }

    @Test
    void whenNothingIsFinalYetTheStoredWeekIsStillReportedSoTheCallerCanFallBack() {
        seedLeague(1);
        fetched(1, false);

        var s = scoredWeeks.of(LEAGUE);
        assertEquals(1, s.latestStored());
        assertEquals(0, s.latestFinal());
        assertFalse(s.isFinal(1));
    }

    @Test
    void aLeagueWithNoFetchRowsAtAllTreatsEveryStoredWeekAsFinal() {
        seedLeague(1, 2, 3);

        var s = scoredWeeks.of(LEAGUE);
        assertEquals(3, s.latestStored());
        assertEquals(3, s.latestFinal(), "ingested before 009: nothing says any week is partial");
        assertTrue(s.isFinal(3));
    }

    /** The championship week can never satisfy leg > week, so its row stays non-final; 009 stops fetching the season. */
    @Test
    void aLoadedCompleteSeasonIsFinalEvenWhereItsLastWeekRowIsNot() {
        seedLeague(1, 2, 3);
        fetched(1, true);
        fetched(2, true);
        fetched(3, false);
        jdbc.update("insert into league_refresh (league_id, last_success_at, loaded_complete) values (?, now(), true)", LEAGUE);

        var s = scoredWeeks.of(LEAGUE);
        assertEquals(3, s.latestFinal());
        assertTrue(s.isFinal(3));
    }

    @Test
    void aFetchRowForAWeekThatIsNotStoredIsIgnored() {
        seedLeague(1);
        fetched(1, true);
        fetched(5, true);

        assertEquals(1, scoredWeeks.of(LEAGUE).latestFinal());
    }

    @Test
    void nothingStoredIsZeroZero() {
        seedLeague();
        var s = scoredWeeks.of(LEAGUE);
        assertEquals(0, s.latestStored());
        assertEquals(0, s.latestFinal());
    }
}
