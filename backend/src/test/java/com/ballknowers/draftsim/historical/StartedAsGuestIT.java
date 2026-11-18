package com.ballknowers.draftsim.historical;

import org.junit.jupiter.api.AfterAll;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spec 076 T016: {@code historical_game.started_as_guest} is written at insert from the owner key, through the
 * real service, and a later claim leaves it alone. Runs on the made-up 1895 season; rows are prefixed
 * {@code it-usage-} and deleted before and after.
 *
 * <p>SKIPS when Postgres is not reachable, so read the skip count.
 */
@SpringBootTest
class StartedAsGuestIT {

    private static final String GUEST = "guest:00000076-0000-4000-8000-0000000000a1";
    private static final String MEMBER = "it-usage-sag-member";
    private static final String CLAIMER = "it-usage-sag-claimer";

    private static String url() {
        return System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
    }

    @BeforeAll
    static void requiresPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url(), "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping started-as-guest IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameService games;

    @BeforeEach
    void fresh() {
        cleanup(jdbc);
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
    }

    @AfterAll
    static void removeEverything() {
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url(), "draftsim", "draftsim"));
            cleanup(j);
            HistoricalSyntheticSeason.clean(j);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was written either.
        }
    }

    private static void cleanup(JdbcTemplate j) {
        j.update("delete from historical_game where sleeper_user_id like 'it-usage-sag-%'"
                + " or sleeper_user_id = ?", GUEST);
    }

    private Boolean started(long id) {
        return jdbc.queryForObject("select started_as_guest from historical_game where id = ?", Boolean.class, id);
    }

    @Test
    void aGuestGameStoresTrueAndAMemberGameStoresFalseNeverNull() {
        long guest = games.create(GUEST, "NBA", 3, HistoricalSyntheticSeason.S).id();
        long member = games.create(MEMBER, "NBA", 3, HistoricalSyntheticSeason.S).id();
        assertNotNull(started(guest), "the insert always records it");
        assertNotNull(started(member));
        assertEquals(Boolean.TRUE, started(guest));
        assertEquals(Boolean.FALSE, started(member));
    }

    @Test
    void claimingTheGuestsGamesLeavesTheStartedAsGuestFlag() {
        long guest = games.create(GUEST, "NBA", 3, HistoricalSyntheticSeason.S).id();
        var claimed = games.claim(GUEST, CLAIMER);
        assertEquals(1, claimed.moved());
        assertEquals(CLAIMER, jdbc.queryForObject("select sleeper_user_id from historical_game where id = ?", String.class, guest));
        assertEquals(Boolean.TRUE, started(guest), "it started as a guest's game and stays one");
    }

    @Test
    void theColumnIsNullableWithNoDefaultSoAnOlderInsertStillWorks() {
        // what code that predates the column does: an insert that never names it
        jdbc.update("insert into historical_game (sleeper_user_id, season_start, teams, rounds, reversal_round,"
                + " roster_positions, scoring, user_slot, rng_seed, current_pick_no, board, status, sport)"
                + " values ('it-usage-sag-old', ?, 2, 1, 0, '{G}', '{}'::jsonb, 1, 1, 1, '[]'::jsonb, 'DRAFTING', 'NBA')",
                HistoricalSyntheticSeason.S);
        assertEquals(null, jdbc.queryForObject("select started_as_guest from historical_game where sleeper_user_id = 'it-usage-sag-old'", Boolean.class));
        assertEquals("YES", jdbc.queryForObject("select is_nullable from information_schema.columns"
                + " where table_name = 'historical_game' and column_name = 'started_as_guest'", String.class));
        assertEquals(null, jdbc.queryForObject("select column_default from information_schema.columns"
                + " where table_name = 'historical_game' and column_name = 'started_as_guest'", String.class));
    }
}
