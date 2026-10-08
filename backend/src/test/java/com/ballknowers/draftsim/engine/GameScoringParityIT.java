package com.ballknowers.draftsim.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 022 T006 / N3: the rewritten {@link GameScoringService#score} must equal the PRE-change algorithm,
 * kept below as a private copy, on every stored game x every league of that sport-season. SKIPS (with a
 * message) when the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class GameScoringParityIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping GameScoringParityIT");
    }

    @Autowired private GameScoringService service;
    @Autowired private JdbcTemplate jdbc;

    /** The algorithm exactly as it was before spec 022 (single loop, running total, round2). */
    private static double oldScore(Map<String, ? extends Number> scoring, Map<String, ?> stats) {
        if (scoring == null || stats == null) return 0.0;
        double total = 0.0;
        for (Map.Entry<String, ? extends Number> e : scoring.entrySet()) {
            Object raw = stats.get(e.getKey());
            if (!(raw instanceof Number n)) continue;
            total += e.getValue().doubleValue() * n.doubleValue();
        }
        return Math.round(total * 100.0) / 100.0;
    }

    @Test
    void newScoreEqualsPreChangeScoreOnEveryStoredGame() throws Exception {
        long comparisons = 0;
        long mismatches = 0;
        List<String> examples = new ArrayList<>();
        for (String sport : List.of("nba", "nfl")) {
            List<String> scoringJsons = jdbc.queryForList(
                    "select scoring_json::text from league where sport = ? and season = 2025", String.class, sport);
            Assumptions.assumeFalse(scoringJsons.isEmpty(), "no " + sport + " 2025 league in the local database");
            List<Map<String, Double>> leagues = new ArrayList<>();
            for (String j : scoringJsons) {
                leagues.add(JSON.readValue(j, new TypeReference<Map<String, Double>>() { }));
            }
            List<String> games = jdbc.queryForList("""
                    select stats::text from player_game
                    where sport = ? and season = 2025 and left(sleeper_player_id, 5) <> 'TEAM_'
                    """, String.class, sport);
            Assumptions.assumeFalse(games.isEmpty(), "no " + sport + " 2025 player_game rows in the local database");
            for (String g : games) {
                Map<String, Object> stats = JSON.readValue(g, new TypeReference<Map<String, Object>>() { });
                for (Map<String, Double> scoring : leagues) {
                    comparisons++;
                    double expected = oldScore(scoring, stats);
                    double actual = service.score(scoring, stats);
                    if (Double.compare(expected, actual) != 0) {
                        mismatches++;
                        if (examples.size() < 5) examples.add(sport + " " + stats + " old=" + expected + " new=" + actual);
                    }
                }
            }
            System.out.println("REPORT parity " + sport + " games=" + games.size() + " leagues=" + leagues.size());
        }
        String msg = "REPORT parity comparisons=" + comparisons + " mismatches=" + mismatches + " " + examples;
        System.out.println(msg);
        assertTrue(comparisons > 0, msg);
        assertEquals(0, mismatches, msg);
    }
}
