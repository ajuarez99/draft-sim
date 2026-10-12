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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/030-post-draft-season-view T009: the real SQL and binds of
 * {@link PlayerSeasonProjectionRepository} -- jsonb from a JSON string and timestamptz from an
 * OffsetDateTime (AGENTS.md bug class 3). Uses a throwaway sport key. SKIPS when the local Postgres
 * is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class PlayerSeasonProjectionRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final String SPORT = "it-season-proj";
    private static final int SEASON = 2099;

    /** Player 1970's real per-game line, measured 2026-10-11 (research R1). */
    private static final String LINE_1970 = """
            {"pts": 31.88, "reb": 7.71, "ast": 8.17, "stl": 1.59, "blk": 0.48, "to": 3.77,
             "tpm": 3.76, "dd": 0.56, "td": 0.14, "gp": 1.0}
            """;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping season projection repository IT");
    }

    @Autowired private PlayerSeasonProjectionRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from player_season_projection where sport = ?", SPORT);
    }

    private static PlayerSeasonProjectionRepository.Row row(String id, String json, Instant modified) {
        return new PlayerSeasonProjectionRepository.Row(id, json, "PER_GAME", "rotowire", modified);
    }

    @Test
    void aPerGameLineRoundTripsThroughJsonb() {
        repo.replaceAll(SPORT, SEASON, List.of(row("1970", LINE_1970, Instant.parse("2026-10-11T12:00:00Z"))));

        Map<String, Double> line = repo.statsByPlayer(SPORT, SEASON).get("1970");
        assertEquals(31.88, line.get("pts"), 1e-9);
        assertEquals(7.71, line.get("reb"), 1e-9);
        assertEquals(8.17, line.get("ast"), 1e-9);
        assertEquals(1.59, line.get("stl"), 1e-9);
        assertEquals(0.48, line.get("blk"), 1e-9);
        assertEquals(3.77, line.get("to"), 1e-9);
        assertEquals(3.76, line.get("tpm"), 1e-9);
        assertEquals(0.56, line.get("dd"), 1e-9);
        assertEquals(0.14, line.get("td"), 1e-9);
    }

    @Test
    void replaceAllDeletesAPlayerMissingFromTheSecondCall() {
        repo.replaceAll(SPORT, SEASON, List.of(
                row("1970", LINE_1970, null), row("2", "{\"pts\": 10.0}", null)));
        var second = repo.replaceAll(SPORT, SEASON, List.of(row("1970", LINE_1970, null)));

        assertEquals(1, second.rowsStored());
        assertEquals(1, second.rowsDeleted());
        assertEquals(Set.of("1970"), repo.statsByPlayer(SPORT, SEASON).keySet());
    }

    @Test
    void replaceAllUpdatesAnExistingLine() {
        repo.replaceAll(SPORT, SEASON, List.of(row("2", "{\"pts\": 10.0}", null)));
        repo.replaceAll(SPORT, SEASON, List.of(row("2", "{\"pts\": 12.5}", null)));

        assertEquals(12.5, repo.statsByPlayer(SPORT, SEASON).get("2").get("pts"), 1e-9);
    }

    @Test
    void provenanceReturnsTheNewestTimestampsAndTheRowCount() {
        Instant old = Instant.parse("2026-10-10T00:00:00Z");
        Instant newer = Instant.parse("2026-10-11T12:00:00Z");
        repo.replaceAll(SPORT, SEASON, List.of(row("1", "{\"pts\": 1.0}", old), row("2", "{\"pts\": 2.0}", newer)));

        var p = repo.provenance(SPORT, SEASON).orElseThrow();
        assertEquals(2, p.rows());
        assertEquals("rotowire", p.source());
        assertEquals(newer, p.sourceModifiedAt());
        assertNotNull(p.fetchedAt());
        assertTrue(repo.provenance(SPORT, SEASON + 1).isEmpty());
    }
}
