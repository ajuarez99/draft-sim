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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 044 T009: {@code LeagueRepository.gameMode} reads {@code settings_json->>'game_mode'} and is empty, never
 * a default, when the key is absent. Seeds throwaway NBA 2097 rows and deletes them. SKIPS when Postgres is
 * unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class LeagueRepositoryGameModeIT {

    private static final String WITH_MODE = "it-044-gm-1";
    private static final String OTHER_MODE = "it-044-gm-2";
    private static final String NO_MODE = "it-044-gm-none";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping game mode IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueRepository leagues;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from league where sleeper_id in (?, ?, ?)", WITH_MODE, OTHER_MODE, NO_MODE);
    }

    private long league(String sleeperId, String settingsJson) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, settings_json)
                values ('nba', 2097, ?, 'IT game mode', 2, ?::jsonb) returning id
                """, Long.class, sleeperId, settingsJson);
    }

    @Test
    void readsTheStoredModeAndNeverDefaultsAnAbsentOne() {
        long one = league(WITH_MODE, "{\"game_mode\": 1}");
        long two = league(OTHER_MODE, "{\"game_mode\": 2, \"leg\": 3}");
        long none = league(NO_MODE, "{\"leg\": 3}");

        assertEquals(1, leagues.gameMode(one).orElseThrow());
        assertEquals(2, leagues.gameMode(two).orElseThrow());
        assertTrue(leagues.gameMode(none).isEmpty(), "an absent key is empty, not 0 and not 1");
        assertTrue(leagues.gameMode(Long.MAX_VALUE).isEmpty(), "an unknown league is empty");
    }
}
