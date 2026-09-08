package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres regression test for the multi-sport-and-rebrand.md Phase 5 bug
 * caught live: {@link PlayerRepository#findAll(Sport)} parsed the {@code
 * positions text[]} column with {@link Position#fromSleeper(String)}, the
 * single-arg, NFL-defaulting overload -- so every nba player read back with an
 * empty positions list (nba raw codes like "PG"/"SG" are not valid football
 * codes and were silently dropped), and {@link
 * com.ballknowers.draftsim.ingest.BoardService#currentBoard} discards any row
 * whose player has no positions. The write side was never wrong: {@code select
 * positions from player where sport='nba'} showed the correct {@code {PG,SG}}
 * the whole time. The loss happened purely on read.
 *
 * Same reused-real-schema/gated-skip convention as the other {@code *IT} tests
 * in this package (e.g. {@link MockDraftRepositoryIT}).
 */
@SpringBootTest
class PlayerRepositoryNbaPositionRoundTripIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping player position round-trip test");
    }

    @Autowired private PlayerRepository players;
    @Autowired private JdbcTemplate jdbc;

    private static final String SLEEPER_ID = "it-player-nba-position-roundtrip";

    @BeforeEach
    void setUp() {
        jdbc.update("delete from player where sport = 'nba' and sleeper_id = ?", SLEEPER_ID);
        // Written the same way ingest writes it: a real Postgres text[], not a
        // Java List<Position> serialized through this repository's own upsertAll
        // -- so this test cannot pass merely because the write and read sides
        // happen to share a bug.
        jdbc.update("""
                insert into player (sport, sleeper_id, name, positions, team, status)
                values ('nba', ?, ?, '{PG,SG}', 'DEN', 'Active')
                """, SLEEPER_ID, "IT Multi-Position Guard");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from player where sport = 'nba' and sleeper_id = ?", SLEEPER_ID);
    }

    @Test
    void anNbaPlayersMultiplePositionsSurviveTheReadBackThroughFindAllNba() {
        List<Player> nbaPlayers = players.findAll(Sport.NBA);

        Optional<Player> found = nbaPlayers.stream()
                .filter(p -> SLEEPER_ID.equals(p.sleeperId()))
                .findFirst();
        assertTrue(found.isPresent(), "the fixture player must come back from findAll(NBA) at all");

        Player p = found.get();
        // The actual regression: this used to come back as List.of() (both "PG"
        // and "SG" are unknown to the NFL position table Position.fromSleeper(String)
        // silently defaulted to), which BoardService.currentBoard then treated as
        // "unresolvable" and dropped the row entirely. Assert the real positions,
        // not just non-emptiness, so a partial or wrongly-ordered read is caught too.
        assertEquals(List.of(Position.PG, Position.SG), p.positions());
        assertEquals(Sport.NBA, p.sport());
    }
}
