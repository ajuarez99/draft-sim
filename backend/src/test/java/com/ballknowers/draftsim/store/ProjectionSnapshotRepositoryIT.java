package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/036 T007: {@code projection_snapshot} (V36) against real Postgres -- the freeze is a property
 * of the TABLE (check constraint + BEFORE UPDATE trigger), so it is only provable by executing SQL
 * (AGENTS.md bug class 2). SKIPS when Postgres is unreachable at the URL the app itself would use
 * ({@code DB_URL}, default the local 5433), so read the skip count.
 */
@SpringBootTest
class ProjectionSnapshotRepositoryIT {

    /** A season no real data uses, so cleanup can never touch a captured row. */
    static final int SEASON = 9036;

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping snapshot repository IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProjectionSnapshotRepository repo;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from projection_snapshot where season = ?", SEASON);
    }

    private static ProjectionSnapshotRepository.Row row(String player, String game, Map<String, Object> stats,
                                                        Instant fetched, Instant cutoff) {
        return new ProjectionSnapshotRepository.Row("nba", SEASON, 1, player, game, LocalDate.of(2036, 1, 1),
                "LAL", "GSW", stats, Map.of("pts", 20.0), "rotowire", Instant.parse("2036-01-01T00:00:00Z"),
                fetched, cutoff);
    }

    private String statsOf(String player, String game) {
        return jdbc.queryForObject("select stats->>'pts' from projection_snapshot where season = ? "
                + "and sleeper_player_id = ? and game_id = ?", String.class, SEASON, player, game);
    }

    private Instant cutoffOf(String player, String game) {
        return jdbc.queryForObject("select cutoff_at from projection_snapshot where season = ? "
                        + "and sleeper_player_id = ? and game_id = ?", OffsetDateTime.class, SEASON, player, game)
                .toInstant();
    }

    @Test
    void anInsertFetchedAtOrAfterItsCutoffIsRejectedByTheCheck() {
        Instant now = Instant.now();
        // Raw SQL, so this is the table's wall and not the repository's.
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                insert into projection_snapshot (sport, season, week, sleeper_player_id, game_id, game_date,
                                                 stats, fetched_at, cutoff_at)
                values ('nba', ?, 1, 'p', 'g', '2036-01-01', '{"pts": 1}'::jsonb, ?, ?)
                """, SEASON, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        // And through the repository: a late row must fail loudly, which is why the service drops them first.
        assertThrows(DataAccessException.class, () -> repo.upsertPreGame(List.of(
                row("p", "g", Map.of("pts", 1.0), now, now.minusSeconds(1)))));
        assertEquals(0, repo.count("nba", SEASON));
    }

    @Test
    void refreshingAnOpenRowReplacesTheLineAndKeepsTheOriginalCutoff() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(Duration.ofDays(1));
        var first = repo.upsertPreGame(List.of(row("p", "g", Map.of("pts", 10.0), now.minus(Duration.ofHours(5)), cutoff)));
        assertEquals(1, first.written());
        assertEquals(0, first.frozenSkipped());

        // The incoming row proposes a DIFFERENT cutoff, a new line, new season-average and new provenance.
        Instant otherCutoff = now.plus(Duration.ofDays(3));
        var second = repo.upsertPreGame(List.of(new ProjectionSnapshotRepository.Row("nba", SEASON, 1, "p", "g",
                LocalDate.of(2036, 1, 1), "LAL", "GSW", Map.of("pts", 25.0), Map.of("pts", 22.0),
                "rotowire", Instant.parse("2036-01-01T06:00:00Z"), now, otherCutoff)));

        assertEquals(1, second.written());
        assertEquals(0, second.frozenSkipped());
        assertEquals(1, repo.count("nba", SEASON), "refreshed in place, not duplicated");
        assertEquals("25.0", statsOf("p", "g"));
        assertEquals("22.0", jdbc.queryForObject("select season_avg_stats->>'pts' from projection_snapshot where season = ?",
                String.class, SEASON));
        assertEquals(Instant.parse("2036-01-01T06:00:00Z"), jdbc.queryForObject(
                "select source_modified_at from projection_snapshot where season = ?", OffsetDateTime.class, SEASON).toInstant());
        assertEquals(cutoff.toEpochMilli(), cutoffOf("p", "g").toEpochMilli(), "cutoff_at is fixed at first insert");
        assertEquals(now.toEpochMilli(), jdbc.queryForObject("select fetched_at from projection_snapshot where season = ?",
                OffsetDateTime.class, SEASON).toInstant().toEpochMilli());
    }

    @Test
    void aFrozenRowCannotBeChangedByDirectUpdateOrByUpsert() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(1));            // already passed
        Instant fetched = now.minus(Duration.ofDays(2));           // fetched before it: the check holds
        jdbc.update("""
                insert into projection_snapshot (sport, season, week, sleeper_player_id, game_id, game_date,
                                                 stats, fetched_at, cutoff_at)
                values ('nba', ?, 1, 'p', 'g', '2036-01-01', '{"pts": 10.0}'::jsonb, ?, ?)
                """, SEASON, OffsetDateTime.ofInstant(fetched, ZoneOffset.UTC), OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));

        // A direct UPDATE: the trigger returns NULL, so zero rows change and nothing throws.
        int changed = jdbc.update("update projection_snapshot set stats = '{\"pts\": 99}'::jsonb where season = ?", SEASON);
        assertEquals(0, changed);
        int moved = jdbc.update("update projection_snapshot set cutoff_at = ? where season = ?",
                OffsetDateTime.ofInstant(now.plus(Duration.ofDays(30)), ZoneOffset.UTC), SEASON);
        assertEquals(0, moved, "un-freezing by moving the cutoff must not work either");
        assertEquals("10.0", statsOf("p", "g"));

        // An upsert whose own row is internally valid (fetched before ITS cutoff) reaches the trigger and is skipped.
        var res = repo.upsertPreGame(List.of(row("p", "g", Map.of("pts", 77.0),
                now.minus(Duration.ofHours(30)), now.minus(Duration.ofHours(24)))));
        assertEquals(0, res.written());
        assertEquals(1, res.frozenSkipped());
        assertEquals("10.0", statsOf("p", "g"));
        assertEquals(cutoff.toEpochMilli(), cutoffOf("p", "g").toEpochMilli());
    }

    @Test
    void aBatchWithTheSameKeyTwiceIsDeduplicatedNotThrown() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(Duration.ofDays(1));
        var res = repo.upsertPreGame(List.of(
                row("p", "g", Map.of("pts", 1.0), now.minusSeconds(60), cutoff),
                row("p", "g", Map.of("pts", 2.0), now.minusSeconds(30), cutoff),
                row("q", "g", Map.of("pts", 3.0), now.minusSeconds(30), cutoff)));

        assertEquals(2, res.written());
        assertEquals(0, res.frozenSkipped());
        assertEquals(2, repo.count("nba", SEASON));
        assertEquals("2.0", statsOf("p", "g"), "the last duplicate wins");
    }
}
