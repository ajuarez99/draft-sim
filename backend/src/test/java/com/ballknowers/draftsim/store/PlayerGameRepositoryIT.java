package com.ballknowers.draftsim.store;

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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for the {@code player_game} natural key
 * (specs/005-daily-weekly-top-players, T017), same convention as
 * {@code DraftRepositoryAllWithLeagueForIT}: reuses the real Spring context and
 * Flyway-migrated schema against application.yml's local-dev default, and SKIPS
 * rather than fails when that Postgres is unreachable.
 *
 * <p>This is the assertion the unit tests cannot make. The walk issuing the
 * same upserts twice is provable with a mock; those upserts collapsing into one
 * row is a property of {@code unique (sleeper_player_id, game_id)} and the
 * {@code on conflict} clause, and only the database can be asked.
 *
 * <p>It matters because the upsert <b>updates</b> rather than doing nothing:
 * stat corrections land after a game settles, measured at ~25-50 points of
 * drift across a season's roster totals, and a backfill re-run is how a
 * correction reaches this table.
 */
@SpringBootTest
class PlayerGameRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    /** Kept well clear of any real Sleeper id so a stray row is obvious. */
    private static final String PLAYER = "it-005-player";
    private static final String GAME = "it-005-game";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping player_game integration test");
    }

    @Autowired private PlayerGameRepository games;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from player_game where sleeper_player_id like ?", "it-005-%");
    }

    private static PlayerGameRepository.Row row(double pts, String opponent) {
        return new PlayerGameRepository.Row(
                Sport.NBA, 2025, 5, PLAYER, GAME, LocalDate.parse("2025-11-17"),
                opponent, false, "{\"pts\":" + pts + "}");
    }

    private int stored() {
        Integer n = jdbc.queryForObject(
                "select count(*) from player_game where sleeper_player_id = ?", Integer.class, PLAYER);
        return n == null ? 0 : n;
    }

    /** The whole point of the natural key: a re-run updates, it does not accumulate. */
    @Test
    void upsertingTheSameGameTwiceLeavesOneRow() {
        games.upsert(row(36.0, "CHI"));
        assertEquals(1, stored());

        games.upsert(row(36.0, "CHI"));
        assertEquals(1, stored(), "the natural key (sleeper_player_id, game_id) must collapse these");
    }

    /**
     * A stat correction must actually land. An {@code on conflict do nothing}
     * would pass the count assertion above and silently freeze a wrong number.
     */
    @Test
    void aReRunOverwritesACorrectedStatLine() {
        games.upsert(row(36.0, "CHI"));
        games.upsert(row(38.0, "CHI"));

        assertEquals(1, stored());
        String stats = jdbc.queryForObject(
                "select stats::text from player_game where sleeper_player_id = ?", String.class, PLAYER);
        assertTrue(stats.contains("38"), "the corrected stat line must replace the original, got " + stats);
    }

    /** Two games for one player are two rows -- the key is the pair, not the player. */
    @Test
    void twoGamesForOnePlayerAreTwoRows() {
        games.upsert(row(36.0, "CHI"));
        games.upsert(new PlayerGameRepository.Row(
                Sport.NBA, 2025, 5, PLAYER, GAME + "-b", LocalDate.parse("2025-11-19"),
                "NOP", true, "{\"pts\":28.0}"));

        assertEquals(2, stored());
    }

    /** The read path both rankings use, including the nullable fields. */
    @Test
    void readsBackTheWeekWithItsNullableFieldsIntact() {
        games.upsert(new PlayerGameRepository.Row(
                Sport.NBA, 2025, 5, PLAYER, GAME, LocalDate.parse("2025-11-17"),
                null, null, "{\"pts\":36.0}"));

        List<PlayerGameRepository.Row> week = games.forWeek(Sport.NBA, 2025, 5).stream()
                .filter(r -> PLAYER.equals(r.sleeperPlayerId()))
                .toList();

        assertEquals(1, week.size());
        assertEquals(LocalDate.parse("2025-11-17"), week.get(0).gameDate());
        assertNull(week.get(0).opponent(), "an unknown opponent must read back unknown, not blank");
        assertNull(week.get(0).isAway());
    }

    /** A week with no rows is empty, not an error on a page load. */
    @Test
    void anUnstoredWeekReadsEmpty() {
        assertTrue(games.forWeek(Sport.NBA, 2025, 99).stream()
                .noneMatch(r -> PLAYER.equals(r.sleeperPlayerId())));
    }
}
