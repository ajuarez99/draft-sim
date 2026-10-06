package com.ballknowers.draftsim.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/018-draft-grades T012 (research R1). Over every NFL league in the local database, every
 * starter-week is scored through {@link GameScoringService} and compared with the points Sleeper
 * credited. Mismatches must be 0, and a starter with no {@code player_game} row must have been
 * credited 0.0. Counts are logged per league.
 *
 * <p>Plain queries in the test only; no production repository method exists for this. Skips (not
 * fails) when the local Postgres at localhost:5433 is unreachable, the same convention as the
 * other ITs. No Spring context is needed.
 */
class NflScoringParityIT {

    private static final Logger log = LoggerFactory.getLogger(NflScoringParityIT.class);
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping NflScoringParityIT");
    }

    @Test
    void everyNflStarterWeekMatchesSleeperCredit() throws Exception {
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(JDBC_URL, USER, PASSWORD));
        GameScoringService service = new GameScoringService();

        List<Map<String, Object>> leagues = jdbc.sql(
                "select id, season, scoring_json::text as scoring from league where sport = 'nfl' order by id")
                .query().listOfRows();
        assertFalse(leagues.isEmpty(), "no NFL leagues in the database");

        for (Map<String, Object> lg : leagues) {
            long leagueId = ((Number) lg.get("id")).longValue();
            int season = ((Number) lg.get("season")).intValue();
            Map<String, Double> scoring = JSON.readValue((String) lg.get("scoring"),
                    new TypeReference<Map<String, Double>>() {});

            // One row per starter-week. The player_game row is looked up for the same
            // sport/season/week; stats is null when there is none. jsonb_exists() is the
            // function form of the ? operator, which JDBC would read as a bind marker.
            List<Map<String, Object>> rows = jdbc.sql("""
                    select r.week as week, s.pid as pid,
                           (r.players_points ->> s.pid)::float8 as credited,
                           (select g.stats::text from player_game g
                             where g.sport = 'nfl' and g.season = r.season and g.week = r.week
                               and g.sleeper_player_id = s.pid limit 1) as stats
                      from roster_week_points r
                      cross join lateral jsonb_array_elements_text(r.starters) as s(pid)
                     where r.league_id = :lid and s.pid <> '0' and jsonb_exists(r.players_points, s.pid)
                    """).param("lid", leagueId).query().listOfRows();

            int matched = 0, mismatched = 0, noGameRow = 0;
            StringBuilder bad = new StringBuilder();
            for (Map<String, Object> row : rows) {
                double credited = ((Number) row.get("credited")).doubleValue();
                String statsJson = (String) row.get("stats");
                if (statsJson == null) {
                    noGameRow++;
                    if (Math.abs(credited) > 0.005) {
                        bad.append(String.format("no game row but credited %.2f: week %s pid %s%n",
                                credited, row.get("week"), row.get("pid")));
                    }
                    continue;
                }
                Map<String, Object> stats = JSON.readValue(statsJson, new TypeReference<Map<String, Object>>() {});
                double computed = service.score(scoring, stats);
                if (Math.abs(computed - credited) <= 0.005) {
                    matched++;
                } else {
                    mismatched++;
                    if (mismatched <= 10) {
                        bad.append(String.format("MISMATCH week %s pid %s computed %.2f credited %.2f%n",
                                row.get("week"), row.get("pid"), computed, credited));
                    }
                }
            }
            log.info("NFL scoring parity league {} season {}: matched={} mismatched={} noGameRow={}",
                    leagueId, season, matched, mismatched, noGameRow);
            System.out.printf("PARITY league %d season %d: matched=%d mismatched=%d noGameRow=%d%n",
                    leagueId, season, matched, mismatched, noGameRow);
            assertEquals(0, mismatched, "league " + leagueId + ":\n" + bad);
            assertEquals(0, bad.length(), "league " + leagueId + ":\n" + bad);
        }
    }
}
