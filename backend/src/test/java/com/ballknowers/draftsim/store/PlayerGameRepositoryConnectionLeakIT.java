package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the Hikari connection leak found live in
 * specs/008-season-superlatives verification (2026-09-23): after ~10
 * superlatives page loads, {@code pg_stat_activity} showed every Hikari
 * connection idle-held after {@code select distinct sleeper_player_id from
 * player_game ...} -- {@link PlayerGameRepository#playersWithGames} called
 * JdbcClient's {@code query(...).stream()}, which keeps its connection open
 * until the returned {@code Stream} itself is closed, and this call site
 * never closed it. It was dead code on {@code main} (spec 005) until
 * {@code SeasonSuperlativesService.absenceSuperlative} started calling it
 * once per page load.
 *
 * <p>A tiny pool (3) and a short connection-timeout (2s), set only for this
 * test's own Spring context via {@link TestPropertySource}, so a reintroduced
 * leak fails fast here instead of needing the default pool of 10 and a long
 * wait. Calling the method more than 3x the pool size (25 calls against a
 * pool of 3) must still return every time -- on the leaking code this fails
 * by the 4th call, once every pool connection is held by a closed-but-never-
 * released stream.
 *
 * <p>Verified against both sides by hand: reverting {@code playersWithGames}
 * to {@code .query(String.class).stream().collect(Collectors.toSet())} makes
 * this test fail (a {@code SQLTransientConnectionException} on/around the
 * 4th call); the {@code .set()} fix in place, it passes.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=2000"
})
class PlayerGameRepositoryConnectionLeakIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping connection-leak regression test");
    }

    @Autowired private PlayerGameRepository games;

    @Test
    void playersWithGamesNeverHoldsAConnectionOpenAcrossManyCalls() {
        // A season with no stored rows -- the leak doesn't depend on result
        // size, only on the query(...).stream() chain never being closed.
        for (int i = 0; i < 25; i++) {
            int call = i;
            Set<String> result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> games.playersWithGames(Sport.NFL, 2099),
                    "call " + call + " hung or failed -- a connection from an earlier call "
                            + "was never released back to the pool");
            assertNotNull(result);
        }
    }
}
