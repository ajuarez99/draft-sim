package com.ballknowers.draftsim.store;

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
 * specs/002-league-history-record-book, data-model R9/R10.
 *
 * <p>The selection rules live in SQL, so a Mockito test cannot reach them. Both
 * traps are seeded here against real Postgres rather than asserted against
 * whatever this developer's database happens to hold, so the test means the
 * same thing on a fresh machine.
 */
@SpringBootTest
class PowerRankingFinalRankIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    /** Well outside any real league id, so a failed cleanup cannot collide. */
    private static final long LEAGUE = 999_002L;

    @Autowired private PowerRankingRepository rankings;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping final-rank test");
    }

    private void seedLeague() {
        jdbc.update("""
                insert into league (id, sport, season, sleeper_id, name, total_rosters)
                values (?, 'nfl', 2099, ?, 'final-rank fixture', 2)
                on conflict (id) do nothing
                """, LEAGUE, "fixture-" + LEAGUE);
    }

    private void seedSnapshot(int week, String kind, int... ranksByRoster) {
        Long id = jdbc.queryForObject("""
                insert into power_ranking (league_id, season, week, kind) values (?, 2099, ?, ?)
                on conflict (league_id, season, week, kind) do update set created_at = now()
                returning id
                """, Long.class, LEAGUE, week, kind);
        for (int i = 0; i < ranksByRoster.length; i++) {
            jdbc.update("insert into power_ranking_entry (ranking_id, roster_id, rank) values (?, ?, ?)",
                    id, i + 1, ranksByRoster[i]);
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from power_ranking where league_id = ?", LEAGUE);
        jdbc.update("delete from league where id = ?", LEAGUE);
    }

    /**
     * R9 -- week 0 is the preseason baseline, not a result.
     *
     * <p>The live 2026 season is exactly this shape: its only COMPUTED_REALIZED
     * snapshot is week 0. A plain max(week) would publish a guess made before a
     * ball was thrown as the final standing, and nothing would throw.
     */
    @Test
    void week0BaselineIsNeverTreatedAsAFinalRank() {
        seedLeague();
        seedSnapshot(0, "COMPUTED_REALIZED", 1, 2);

        assertTrue(rankings.finalRealizedRanks(LEAGUE).isEmpty(),
                "week 0 is the preseason baseline and must not answer 'how did the season finish'");
    }

    /**
     * R10 -- a commissioner's ordering is an opinion, not a result.
     *
     * <p>Seeded here with the COMMISSIONER snapshot at a LATER week than the
     * realized one, which is the arrangement that breaks a kind-agnostic
     * max(week) -- and is the arrangement the live 2026 season is in.
     */
    @Test
    void commissionerSnapshotNeverAnswersForTheSeasonResult() {
        seedLeague();
        seedSnapshot(14, "COMPUTED_REALIZED", 1, 2);
        seedSnapshot(17, "COMMISSIONER", 2, 1);

        List<PowerRankingRepository.FinalRank> out = rankings.finalRealizedRanks(LEAGUE);

        assertEquals(2, out.size());
        assertEquals(14, out.get(0).week(), "the realized snapshot answers, not the later commissioner one");
        assertEquals(1, out.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow().rank());
    }

    /** The greatest realized week past 0 wins -- a season has many snapshots. */
    @Test
    void latestRealizedWeekPastZeroWins() {
        seedLeague();
        seedSnapshot(0, "COMPUTED_REALIZED", 2, 1);
        seedSnapshot(8, "COMPUTED_REALIZED", 2, 1);
        seedSnapshot(17, "COMPUTED_REALIZED", 1, 2);

        List<PowerRankingRepository.FinalRank> out = rankings.finalRealizedRanks(LEAGUE);

        assertEquals(17, out.get(0).week());
        assertEquals(1, out.stream().filter(r -> r.rosterId() == 1).findFirst().orElseThrow().rank());
    }

    /** A season with nothing stored answers empty rather than throwing. */
    @Test
    void seasonWithNoSnapshotsAnswersEmpty() {
        seedLeague();
        assertTrue(rankings.finalRealizedRanks(LEAGUE).isEmpty());
    }
}
