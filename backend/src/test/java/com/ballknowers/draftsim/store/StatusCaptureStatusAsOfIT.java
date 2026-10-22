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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/042 T004: the real SQL of {@link StatusCaptureRepository#statusAsOf} -- the greatest of
 * {@code status_capture} (max across weeks) and the daily {@code PLAYERS} capture, empty when neither
 * exists (review F2). Uses a throwaway sport key. SKIPS when the local Postgres is unreachable, so the
 * caller must read the skip count.
 */
@SpringBootTest
class StatusCaptureStatusAsOfIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final String SPORT = "it-asof";
    private static final int SEASON = 2098;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping status-as-of IT");
    }

    @Autowired private StatusCaptureRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from status_capture where sport = ?", SPORT);
        jdbc.update("delete from daily_capture where sport = ?", SPORT);
    }

    private void capture(int week, String at) {
        jdbc.update("insert into status_capture (sport, season, week, captured_at) values (?, ?, ?, ?)",
                SPORT, SEASON, week, OffsetDateTime.parse(at));
    }

    private void daily(String date, String at) {
        jdbc.update("insert into daily_capture (sport, capture_date, kind, completed_at) values (?, ?::date, 'PLAYERS', ?)",
                SPORT, date, OffsetDateTime.parse(at));
    }

    @Test
    void emptyWhenNothingWasEverCaptured() {
        assertTrue(repo.statusAsOf(SPORT, SEASON).isEmpty(), "unknown, not fresh");
    }

    @Test
    void theLatestWeekOfTheStatusCaptureWins() {
        capture(1, "2098-10-01T11:00:00Z");
        capture(3, "2098-10-15T11:05:00Z");
        capture(2, "2098-10-08T11:00:00Z");

        assertEquals(Instant.parse("2098-10-15T11:05:00Z"), repo.statusAsOf(SPORT, SEASON).orElseThrow());
    }

    @Test
    void aLaterDailyPlayersCaptureBeatsAnOlderStatusCapture() {
        capture(1, "2098-10-01T11:00:00Z");
        daily("2098-10-20", "2098-10-20T11:02:00Z");

        assertEquals(Instant.parse("2098-10-20T11:02:00Z"), repo.statusAsOf(SPORT, SEASON).orElseThrow());
    }

    @Test
    void theDailyCaptureAloneIsEnoughPreseason() {
        // Preseason: players are rewritten but status_capture is not (review F2).
        daily("2098-10-05", OffsetDateTime.of(2098, 10, 5, 11, 1, 0, 0, ZoneOffset.UTC).toString());

        assertEquals(Instant.parse("2098-10-05T11:01:00Z"), repo.statusAsOf(SPORT, SEASON).orElseThrow());
    }

    @Test
    void anotherSeasonsStatusCaptureIsIgnored() {
        jdbc.update("insert into status_capture (sport, season, week, captured_at) values (?, ?, 1, ?)",
                SPORT, SEASON - 1, OffsetDateTime.parse("2098-01-01T00:00:00Z"));

        assertTrue(repo.statusAsOf(SPORT, SEASON).isEmpty());
    }
}
