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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/014 T027: the real SQL and JDBC binds of {@link SportTrendingRepository} against Postgres
 * (timestamptz via OffsetDateTime, date via LocalDate). Uses a throwaway sport key so it never
 * touches the real nba/nfl rows. SKIPS when the local Postgres is unreachable, so the caller
 * must read the skip count.
 */
@SpringBootTest
class SportTrendingRepositoryIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final String SPORT = "it-trending";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping sport trending repository IT");
    }

    @Autowired private SportTrendingRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        // sport_trending rows cascade from the fetch row.
        jdbc.update("delete from sport_trending_fetch where sport = ?", SPORT);
    }

    private static List<SportTrendingRepository.Entry> entries(int n) {
        List<SportTrendingRepository.Entry> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(new SportTrendingRepository.Entry("p" + i, 1000 - i));
        return out;
    }

    @Test
    void replaceThenReadRoundTripsTwentyFiveEntriesInRankOrderWithExactFetchedAt() {
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 1, 12, 34, 56, 123_000_000, ZoneOffset.UTC);

        repo.replace(SPORT, 24, 2026, LocalDate.of(2026, 9, 10), entries(25), at);

        var snap = repo.read(SPORT).orElseThrow();
        assertEquals(entries(25), snap.entries());
        assertTrue(at.isEqual(snap.fetchedAt()), "fetchedAt " + snap.fetchedAt() + " vs " + at);
        assertEquals(24, snap.lookbackHours());
        assertEquals(2026, snap.leagueSeason());
        assertEquals(LocalDate.of(2026, 9, 10), snap.seasonStartDate());
        assertNull(snap.lastFailureAt());
        assertNull(snap.lastFailure());
    }

    @Test
    void aSecondReplaceLeavesExactlyTheNewEntriesAndNullsCanBeStored() {
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        repo.replace(SPORT, 24, 2026, LocalDate.of(2026, 9, 10), entries(25), at);

        repo.replace(SPORT, 24, null, null, entries(3), at.plusHours(2));

        var snap = repo.read(SPORT).orElseThrow();
        assertEquals(entries(3), snap.entries());
        assertEquals(3, jdbc.queryForObject("select count(*) from sport_trending where sport = ?", Integer.class, SPORT));
        assertNull(snap.leagueSeason());
        assertNull(snap.seasonStartDate());
        assertTrue(at.plusHours(2).isEqual(snap.fetchedAt()));
    }

    @Test
    void anEmptyListIsFetchedNotNeverFetched() {
        repo.replace(SPORT, 24, 2026, null, List.of(), OffsetDateTime.now(ZoneOffset.UTC));

        var snap = repo.read(SPORT).orElseThrow();
        assertNotNull(snap.fetchedAt());
        assertTrue(snap.entries().isEmpty());
    }

    @Test
    void recordFailureAfterASuccessKeepsTheListAndFetchedAtAndSetsTheFailure() {
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        repo.replace(SPORT, 24, 2026, LocalDate.of(2026, 9, 10), entries(25), at);
        OffsetDateTime failedAt = at.plusMinutes(90);

        repo.recordFailure(SPORT, failedAt, "IllegalStateException: sleeper 503");

        var snap = repo.read(SPORT).orElseThrow();
        assertEquals(25, snap.entries().size());
        assertTrue(at.isEqual(snap.fetchedAt()), "fetchedAt must keep the last SUCCESS");
        assertTrue(failedAt.isEqual(snap.lastFailureAt()));
        assertEquals("IllegalStateException: sleeper 503", snap.lastFailure());
        assertEquals(2026, snap.leagueSeason());
    }

    @Test
    void recordFailureOnAFreshSportCreatesARowWithNullFetchedAtAndNoEntries() {
        OffsetDateTime failedAt = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        repo.recordFailure(SPORT, failedAt, "boom");

        var snap = repo.read(SPORT).orElseThrow();
        assertNull(snap.fetchedAt());
        assertTrue(snap.entries().isEmpty());
        assertEquals(24, snap.lookbackHours());
        assertEquals("boom", snap.lastFailure());
        assertTrue(failedAt.isEqual(snap.lastFailureAt()));
    }

    @Test
    void aSuccessAfterAFailureKeepsTheFailureForHistoryAndSetsFetchedAt() {
        repo.recordFailure(SPORT, OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC), "boom");
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 1, 13, 0, 0, 0, ZoneOffset.UTC);

        repo.replace(SPORT, 24, 2026, null, entries(2), at);

        var snap = repo.read(SPORT).orElseThrow();
        assertTrue(at.isEqual(snap.fetchedAt()));
        assertEquals(2, snap.entries().size());
    }

    @Test
    void anUnknownSportReadsEmpty() {
        assertTrue(repo.read(SPORT).isEmpty());
    }
}
