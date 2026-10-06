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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 019 T006: the V28 {@code text[]} / {@code timestamptz} round trip through the real
 * repository (lessons bug class #3: a bind that compiles and fails at runtime). SKIPS when the
 * local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class RosterSeasonRepositoryPlayersIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String SLEEPER_ID = "it-roster-players-league";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping");
    }

    @Autowired private RosterSeasonRepository repo;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nba', 2099, ?, 'IT', 2) returning id",
                Long.class, SLEEPER_ID);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", SLEEPER_ID);
    }

    private static RosterSeasonRepository.Upsert row(long leagueId, int roster, List<String> players, OffsetDateTime at) {
        return new RosterSeasonRepository.Upsert(leagueId, null, roster, 1, 2, 0, 10.5, 9.5, 12.0, null, players, at);
    }

    private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 10, 11, 2, 10, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime T2 = OffsetDateTime.of(2026, 10, 11, 3, 10, 0, 0, ZoneOffset.UTC);

    @Test
    void roundTripsAnArrayAnEmptyArrayAndNull() {
        repo.upsertAll(List.of(row(leagueId, 1, List.of("1", "2"), T1), row(leagueId, 2, List.of(), T2)));

        Optional<RosterSeasonRepository.Rostered> r = repo.rosteredPlayers(leagueId);
        assertTrue(r.isPresent());
        assertEquals(2, r.get().byPlayer().size());
        assertEquals(1, r.get().byPlayer().get("1"));
        assertEquals(1, r.get().byPlayer().get("2"));
        assertEquals(T1.toInstant(), r.get().fetchedAt().toInstant(), "the MIN fetch time across rows (N9)");

        // raw SQL, not through the repository's own read
        Integer card1 = jdbc.queryForObject(
                "select cardinality(players) from roster_season where league_id = ? and roster_id = 1", Integer.class, leagueId);
        Integer card2 = jdbc.queryForObject(
                "select cardinality(players) from roster_season where league_id = ? and roster_id = 2", Integer.class, leagueId);
        assertEquals(2, card1);
        assertEquals(0, card2, "an empty array is stored as empty, not null");

        repo.upsertAll(List.of(row(leagueId, 2, null, null)));
        Boolean isNull = jdbc.queryForObject(
                "select players is null and players_fetched_at is null from roster_season where league_id = ? and roster_id = 2",
                Boolean.class, leagueId);
        assertTrue(isNull);
        assertTrue(repo.rosteredPlayers(leagueId).isEmpty(), "any null row means ownership is unknown");
    }

    @Test
    void aPlayerOnTwoRostersMapsToTheFirstRosterId() {
        repo.upsertAll(List.of(row(leagueId, 2, List.of("9"), T1), row(leagueId, 1, List.of("9"), T1)));
        assertEquals(1, repo.rosteredPlayers(leagueId).orElseThrow().byPlayer().get("9"));
    }

    @Test
    void reUpsertOverwrites() {
        repo.upsertAll(List.of(row(leagueId, 1, List.of("1", "2"), T1)));
        repo.upsertAll(List.of(row(leagueId, 1, List.of("3"), T2)));
        RosterSeasonRepository.Rostered r = repo.rosteredPlayers(leagueId).orElseThrow();
        assertEquals(1, r.byPlayer().size());
        assertTrue(r.byPlayer().containsKey("3"));
        assertEquals(T2.toInstant(), r.fetchedAt().toInstant());
    }

    @Test
    void aLeagueWithNoRowsIsEmpty() {
        assertTrue(repo.rosteredPlayers(leagueId).isEmpty());
    }
}
