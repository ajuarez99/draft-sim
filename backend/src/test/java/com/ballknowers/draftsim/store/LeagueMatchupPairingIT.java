package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.ScoredRow;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 045 code review S3: the Java pairing rule {@link LeagueMatchupRepository#pair} (used by the live
 * week) and the SQL self-join {@link LeagueMatchupRepository#pairedWithScores} (used by every stored week)
 * are fed the SAME rows against real Postgres and must agree, so the two implementations of one rule cannot
 * drift unnoticed. SKIPS when Postgres is unreachable, so read the skip count.
 */
@SpringBootTest
class LeagueMatchupPairingIT {

    static final String LEAGUE = "it-pairing-league";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping pairing IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMatchupRepository matchups;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions)
                values ('nba', 2026, ?, 'IT pairing', 12, 'in_season', '{PG}') returning id
                """, Long.class, LEAGUE);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
    }

    private void store(int roster, Integer matchupId, Double points) {
        if (points != null) {
            jdbc.update("""
                    insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                    values (?, 2026, 1, ?, ?, '{}'::jsonb)
                    """, leagueId, roster, points);
        }
        jdbc.update("""
                insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                values (?, 2026, 1, ?, ?)
                """, leagueId, roster, matchupId);
    }

    /** Inserted out of roster order on purpose. */
    private static List<ScoredRow> fixture() {
        return List.of(
                new ScoredRow(7, 3, 101.5),
                new ScoredRow(5, 1, 99.0),
                new ScoredRow(8, null, 0.0),     // null matchup_id: pairs with nothing
                new ScoredRow(3, 3, 120.25),     // matchup 3 holds THREE rosters: 3, 4, 7
                new ScoredRow(1, 1, 130.0),
                new ScoredRow(6, 2, 88.0),
                new ScoredRow(4, 3, 77.5),
                new ScoredRow(2, 2, 88.0));
    }

    private static List<String> asKeys(List<PairedGame> sql) {
        return sql.stream().map(g -> g.aRosterId() + "-" + g.bRosterId() + ":"
                + g.aPoints().doubleValue() + "/" + g.bPoints().doubleValue()).toList();
    }

    @Test
    void theJavaPairingAgreesWithTheSqlPairing_onTheSameRows() {
        List<ScoredRow> rows = fixture();
        for (ScoredRow r : rows) store(r.rosterId(), r.matchupId(), r.points());

        List<PairedGame> sql = matchups.pairedWithScores(List.of(leagueId), WeekBound.ALL_WEEKS);
        List<LeagueMatchupRepository.Pair> java = LeagueMatchupRepository.pair(rows);

        assertEquals(5, sql.size(), "1-5, 2-6, and the three pairs of matchup 3");
        // order: the specified one is the lower roster id; SQL leaves the higher one unspecified
        assertEquals(sql.stream().map(PairedGame::aRosterId).toList(),
                java.stream().map(p -> p.a().rosterId()).toList(), "same order of the lower roster");
        List<String> sqlKeys = new ArrayList<>(asKeys(sql));
        List<String> javaKeys = new ArrayList<>(java.stream().map(p -> p.a().rosterId() + "-" + p.b().rosterId()
                + ":" + p.a().points() + "/" + p.b().points()).toList());
        sqlKeys.sort(Comparator.naturalOrder());
        javaKeys.sort(Comparator.naturalOrder());
        assertEquals(sqlKeys, javaKeys, "identical pairs and identical points on each side");
    }

    @Test
    void theOneKnownDifference_sqlDropsARosterWithNoStoredScore_theLiveRowsAlwaysCarryPoints() {
        store(1, 1, 100.0);
        store(2, 1, null);      // matchup_id but no roster_week_points row: a FUTURE fixture
        assertTrue(matchups.pairedWithScores(List.of(leagueId), WeekBound.ALL_WEEKS).isEmpty());
        // the live path never has that shape (Sleeper always sends points), so pair() pairs what it is given
        assertEquals(1, LeagueMatchupRepository.pair(
                List.of(new ScoredRow(1, 1, 100.0), new ScoredRow(2, 1, 0.0))).size());
    }
}
