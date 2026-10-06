package com.ballknowers.draftsim.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/018-draft-grades T011 (research R1, quickstart V1). Pure: no database, so it cannot skip.
 *
 * <p>The fixture holds real rows from "(Foot) Ball Knowers" 2025 (league id 5): the league's full
 * {@code scoring_json} plus starter-weeks whose {@code credited} is the value Sleeper stored in
 * {@code roster_week_points.players_points}. Export SQL: for league 5 / season 2025, every starter
 * ({@code starters} element other than "0") present in {@code players_points}, joined to the
 * {@code player_game} row for the same sport/season/week/player (stats as stored, null when there
 * is none), position from {@code player.positions[1]}, sampled per position by md5(week||pid)
 * with all the zero-credit no-game-row and negative-credit cases kept in.
 *
 * <p>The assertion is that {@link GameScoringService} itself reproduces Sleeper's number. A
 * starter with no game row is credited 0.0.
 */
class NflScoringParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOL = 0.005;

    private record Entry(int week, String pid, String position, Map<String, Object> stats, double credited) {}

    private static Map<String, Double> scoring;
    private static List<Entry> entries;

    static {
        try (InputStream in = NflScoringParityTest.class.getResourceAsStream("/sleeper/nfl-scoring-parity-2025.json")) {
            assertNotNull(in, "fixture missing");
            JsonNode root = JSON.readTree(in);
            scoring = JSON.convertValue(root.get("scoring"), new TypeReference<Map<String, Double>>() {});
            entries = new ArrayList<>();
            for (JsonNode n : root.get("entries")) {
                Map<String, Object> stats = n.get("stats").isNull() ? null
                        : JSON.convertValue(n.get("stats"), new TypeReference<Map<String, Object>>() {});
                entries.add(new Entry(n.get("week").asInt(), n.get("sleeperPlayerId").asText(),
                        n.get("position").asText(), stats, n.get("credited").asDouble()));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final GameScoringService service = new GameScoringService();

    @Test
    void fixtureCoversEveryPositionAndTheEdgeCases() {
        for (String pos : List.of("QB", "RB", "WR", "TE", "K", "DEF")) {
            assertTrue(entries.stream().anyMatch(e -> e.position().equals(pos) && e.stats() != null), pos);
        }
        assertTrue(entries.stream().anyMatch(e -> e.stats() == null), "needs a no-game-row starter");
        assertTrue(entries.stream().anyMatch(e -> e.credited() < 0), "needs a negative game");
    }

    @Test
    void serviceReproducesSleeperCreditedPoints() {
        for (Entry e : entries) {
            if (e.stats() == null) {
                assertEquals(0.0, e.credited(), TOL, "no game row must be credited 0: " + e);
            } else {
                assertEquals(e.credited(), service.score(scoring, e.stats()), TOL,
                        "week " + e.week() + " " + e.position() + " " + e.pid());
            }
        }
    }

    /** Proves the parity test can fail: halve one receiving multiplier and something must break. */
    @Test
    void mutatedScoringIsDetected() {
        Map<String, Double> mutated = new HashMap<>(scoring);
        String key = mutated.containsKey("rec") ? "rec" : "rec_yd";
        mutated.put(key, mutated.get(key) / 2);
        long mismatches = entries.stream()
                .filter(e -> e.stats() != null)
                .filter(e -> Math.abs(service.score(mutated, e.stats()) - e.credited()) > TOL)
                .count();
        assertTrue(mismatches > 0, "halving " + key + " changed no fixture entry");
    }
}
