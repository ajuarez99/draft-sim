package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.ingest.SportSchedule;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/017-nba-schedule-grid T007: the real SQL and JDBC binds of {@link SportScheduleRepository}
 * (date via LocalDate, timestamptz via OffsetDateTime; AGENTS.md bug class 3). Uses a throwaway
 * sport key. SKIPS when the local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class SportScheduleRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final String SPORT = "it-schedule";
    private static final int SEASON = 2099;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping sport schedule repository IT");
    }

    @Autowired private SportScheduleRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from sport_schedule where sport = ?", SPORT);
    }

    private static List<SportSchedule.Game> games(int n) {
        List<SportSchedule.Game> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new SportSchedule.Game("g" + i, 1 + i % 3, LocalDate.of(2099, 10, 1).plusDays(i),
                    "pre_game", "H" + i, "A" + i));
        }
        return out;
    }

    private int rowCount() {
        return jdbc.queryForObject("select count(*) from sport_schedule where sport = ?", Integer.class, SPORT);
    }

    @Test
    void replaceSeasonStoresRowsAndReadsBackDateAndFetchedAtByValue() {
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 5, 12, 34, 56, 123_000_000, ZoneOffset.UTC);

        repo.replaceSeason(SPORT, SEASON, games(5), at);

        assertEquals(5, rowCount());
        List<SportSchedule.Game> back = repo.forSeason(SPORT, SEASON);
        assertEquals(5, back.size());
        SportSchedule.Game g3 = back.stream().filter(g -> g.gameId().equals("g3")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2099, 10, 4), g3.date());
        assertEquals("H3", g3.home());
        assertEquals("A3", g3.away());
        assertEquals("pre_game", g3.status());
        assertEquals(at.toInstant(), repo.fetchedAt(SPORT, SEASON).orElseThrow().toInstant());
    }

    @Test
    void forSeasonIsOrderedByWeekThenDateThenGameId() {
        repo.replaceSeason(SPORT, SEASON, games(9), OffsetDateTime.now(ZoneOffset.UTC));

        List<SportSchedule.Game> back = repo.forSeason(SPORT, SEASON);

        for (int i = 1; i < back.size(); i++) {
            SportSchedule.Game a = back.get(i - 1), b = back.get(i);
            int byWeek = Integer.compare(a.week(), b.week());
            int cmp = byWeek != 0 ? byWeek : a.date().compareTo(b.date());
            assertTrue(cmp <= 0, "out of order at " + i);
        }
    }

    @Test
    void aSecondReplaceWithASmallerListLeavesOnlyTheNewRows() {
        repo.replaceSeason(SPORT, SEASON, games(5), OffsetDateTime.now(ZoneOffset.UTC));

        repo.replaceSeason(SPORT, SEASON, games(2), OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(2, rowCount());
        assertEquals(List.of("g1", "g2"), repo.forSeason(SPORT, SEASON).stream()
                .map(SportSchedule.Game::gameId).sorted().toList());
    }

    @Test
    void anEmptyListIsANoOpAndKeepsTheOldRows() {
        repo.replaceSeason(SPORT, SEASON, games(4), OffsetDateTime.now(ZoneOffset.UTC));

        repo.replaceSeason(SPORT, SEASON, List.of(), OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(4, rowCount());
    }

    @Test
    void aDuplicateGameIdInTheInputDoesNotThrow() {
        List<SportSchedule.Game> dup = new ArrayList<>(games(2));
        dup.add(new SportSchedule.Game("g1", 7, LocalDate.of(2099, 11, 1), "complete", "X", "Y"));

        assertDoesNotThrow(() -> repo.replaceSeason(SPORT, SEASON, dup, OffsetDateTime.now(ZoneOffset.UTC)));

        assertEquals(2, rowCount());
        assertEquals("complete", repo.forSeason(SPORT, SEASON).stream()
                .filter(g -> g.gameId().equals("g1")).findFirst().orElseThrow().status());
    }

    @Test
    void aFailureMidInsertRollsBackToTheOldRows() {
        repo.replaceSeason(SPORT, SEASON, games(3), OffsetDateTime.now(ZoneOffset.UTC));
        // A null game_id violates NOT NULL on the second insert, after the delete has run.
        List<SportSchedule.Game> bad = List.of(
                new SportSchedule.Game("ok", 1, LocalDate.of(2099, 12, 1), "pre_game", "A", "B"),
                new SportSchedule.Game(null, 1, LocalDate.of(2099, 12, 2), "pre_game", "C", "D"));

        assertThrows(RuntimeException.class,
                () -> repo.replaceSeason(SPORT, SEASON, bad, OffsetDateTime.now(ZoneOffset.UTC)));

        assertEquals(3, rowCount());
        assertEquals(List.of("g1", "g2", "g3"), repo.forSeason(SPORT, SEASON).stream()
                .map(SportSchedule.Game::gameId).sorted().toList());
    }
}
