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
 * Real Postgres, because the behaviour under test is a property of the write
 * sequence rather than of any Java branch.
 *
 * {@link PowerRankingRepository#save} is insert-or-touch, delete every entry,
 * re-insert. With an empty entry list that middle step still ran, so a
 * recompute that found nothing to rank DELETED a previously good snapshot's
 * entries and left a hollow {@code power_ranking} row behind. "This week has
 * not been scored yet" could silently destroy last week's real ranking, and
 * the empty row it left made the feature look like it had persisted something.
 *
 * A mocked repository cannot show this; only the actual SQL can.
 */
@SpringBootTest
class PowerRankingEmptySnapshotIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping power-ranking integration test");
    }

    @Autowired private PowerRankingRepository rankings;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void setUp() {
        cleanup();
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-power-empty", "Power Empty", 12);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("delete from league where sleeper_id like ?", "it-league-power-%");
    }

    private int entryCount() {
        Integer n = jdbc.queryForObject("""
                select count(*) from power_ranking pr
                join power_ranking_entry e on e.ranking_id = pr.id
                where pr.league_id = ?
                """, Integer.class, leagueId);
        return n == null ? 0 : n;
    }

    private int snapshotCount() {
        Integer n = jdbc.queryForObject(
                "select count(*) from power_ranking where league_id = ?", Integer.class, leagueId);
        return n == null ? 0 : n;
    }

    private static List<PowerRankingRepository.Entry> twoEntries() {
        return List.of(
                new PowerRankingRepository.Entry(1, null, 1, 120.0, "first"),
                new PowerRankingRepository.Entry(2, null, 2, 90.0, "second"));
    }

    @Test
    void anEmptySaveWritesNoSnapshotAtAll() {
        assertFalse(rankings.save(leagueId, 2026, 1, "COMPUTED_REALIZED", List.of()),
                "an empty ranking is not a fact worth storing");

        assertEquals(0, snapshotCount(), "no hollow power_ranking row may be left behind");
        assertEquals(0, entryCount());
        assertTrue(rankings.forLeague(leagueId).isEmpty());
    }

    @Test
    void aNullEntryListIsRefusedTheSameWay() {
        assertFalse(rankings.save(leagueId, 2026, 1, "COMPUTED_REALIZED", null));
        assertEquals(0, snapshotCount());
    }

    /**
     * The destructive case, and the reason the guard lives in the repository
     * rather than only at the call site: week 1 is ranked, then week 1 is
     * recomputed at a moment when the scoring is unavailable. The good snapshot
     * must survive untouched.
     */
    @Test
    void anEmptyRecomputeLeavesAnExistingSnapshotIntact() {
        assertTrue(rankings.save(leagueId, 2026, 1, "COMPUTED_REALIZED", twoEntries()));
        assertEquals(2, entryCount());

        assertFalse(rankings.save(leagueId, 2026, 1, "COMPUTED_REALIZED", List.of()),
                "the recompute found nothing, so it must decline rather than overwrite");

        assertEquals(1, snapshotCount());
        assertEquals(2, entryCount(), "the previously good ranking must not have been deleted");
        var rows = rankings.forLeague(leagueId);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).rank());
        assertEquals(120.0, rows.get(0).score());
    }

    /** A non-empty recompute still replaces, which is what re-running ingest wants. */
    @Test
    void aNonEmptyRecomputeStillReplacesTheSnapshot() {
        assertTrue(rankings.save(leagueId, 2026, 1, "COMPUTED_REALIZED", twoEntries()));
        assertTrue(rankings.save(leagueId, 2026, 1, "COMPUTED_REALIZED",
                List.of(new PowerRankingRepository.Entry(3, null, 1, 200.0, "only one now"))));

        assertEquals(1, snapshotCount(), "same (league, season, week, kind) key, so still one snapshot");
        assertEquals(1, entryCount(), "the two old entries are replaced, not appended to");
        assertEquals(3, rankings.forLeague(leagueId).get(0).rosterId());
    }
}
