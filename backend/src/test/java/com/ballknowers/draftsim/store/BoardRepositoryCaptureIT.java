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
 * Spec 046 T016: the capture writer and reader against REAL Postgres (the JDBC binds and the numeric columns
 * are exactly the class of thing a mock can't catch -- lessons #2/#3). A capture and its {@code adp_unmatched}
 * rows are one transaction: a failing unmatched insert must leave the previous capture intact.
 *
 * <p>Uses far-future capture dates so it can never replace a real day's rows. SKIPS when Postgres is
 * unreachable, so the caller must read the skip count.
 */
@SpringBootTest
class BoardRepositoryCaptureIT {

    private static final LocalDate DAY_1 = LocalDate.of(2091, 1, 1);
    private static final LocalDate DAY_2 = LocalDate.of(2091, 1, 2);

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping capture repository IT");
    }

    @Autowired private BoardRepository boards;
    @Autowired private JdbcTemplate jdbc;

    private long a, b;

    @BeforeEach
    void seed() {
        clean();
        a = player("a");
        b = player("b");
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from player where sleeper_id like 'it046-cap-%'");
        jdbc.update("delete from adp_unmatched where captured_on in (?, ?)", DAY_1, DAY_2);
    }

    private long player(String tag) {
        return jdbc.queryForObject("insert into player (sport, sleeper_id, name, positions) values ('nba', ?, ?, '{PG}') returning id",
                Long.class, "it046-cap-" + tag, "IT Cap " + tag);
    }

    @Test
    void aCaptureRoundTripsYahooCostAndShareAndTheScoringLabel() {
        boards.saveCapture(Sport.NBA, "yahoo", DAY_1, "redraft",
                List.of(new BoardRepository.CaptureRow(a, 1.4, 77.0, 1.0), new BoardRepository.CaptureRow(b, 120.5, null, null)),
                List.of(new BoardRepository.UnmatchedRow("IT Cap Missing", "BOS", "PG,SG", 30.25, 4.5, 0.75)));

        var latest = boards.latestCaptureDetail(Sport.NBA, "yahoo").orElseThrow();

        assertEquals(DAY_1, latest.capturedOn());
        assertEquals(2, latest.rows().size());
        var first = latest.rows().get(0);
        assertEquals(a, first.playerId());
        assertEquals(1.4, first.adp(), 1e-9);
        assertEquals(77.0, first.avgCost(), 1e-9);
        assertEquals(1.0, first.percentDrafted(), 1e-9);
        assertNull(latest.rows().get(1).avgCost());
        assertNull(latest.rows().get(1).percentDrafted());
        assertEquals(1, latest.unmatched().size());
        assertEquals("IT Cap Missing", latest.unmatched().get(0).name());
        assertEquals("PG,SG", latest.unmatched().get(0).positions());
        assertEquals(30.25, latest.unmatched().get(0).adp(), 1e-9);
        assertEquals("redraft", jdbc.queryForObject(
                "select source_scoring from adp_snapshot where player_id = ? and source = 'yahoo'", String.class, a));
        assertEquals(2, boards.captureSize(Sport.NBA, "yahoo", DAY_1));
    }

    @Test
    void writingTheSameDayAgainReplacesTheMatchedAndTheUnmatchedRows() {
        boards.saveCapture(Sport.NBA, "yahoo", DAY_1, "redraft",
                List.of(new BoardRepository.CaptureRow(a, 1.4, 77.0, 1.0), new BoardRepository.CaptureRow(b, 9.0, 5.0, 0.9)),
                List.of(new BoardRepository.UnmatchedRow("IT Cap Old", null, null, 50.0, null, null)));

        boards.saveCapture(Sport.NBA, "yahoo", DAY_1, "redraft",
                List.of(new BoardRepository.CaptureRow(a, 2.0, 70.0, 1.0)), List.of());

        var latest = boards.latestCaptureDetail(Sport.NBA, "yahoo").orElseThrow();
        assertEquals(1, latest.rows().size());
        assertEquals(2.0, latest.rows().get(0).adp(), 1e-9);
        assertTrue(latest.unmatched().isEmpty(), "the day's old unmatched rows go with the old capture");
    }

    @Test
    void theNewestDateWinsAndAnEarlierCaptureIsLeftAlone() {
        boards.saveCapture(Sport.NBA, "yahoo", DAY_1, "redraft", List.of(new BoardRepository.CaptureRow(a, 1.0, 80.0, 1.0)), List.of());
        boards.saveCapture(Sport.NBA, "yahoo", DAY_2, "redraft", List.of(new BoardRepository.CaptureRow(b, 3.0, 60.0, 1.0)), List.of());

        var latest = boards.latestCaptureDetail(Sport.NBA, "yahoo").orElseThrow();

        assertEquals(DAY_2, latest.capturedOn());
        assertEquals(b, latest.rows().get(0).playerId());
        assertEquals(1, boards.captureSize(Sport.NBA, "yahoo", DAY_1));
    }

    @Test
    void aFailingUnmatchedInsertRollsTheWholeCaptureBackSoThePreviousOneStays() {
        boards.saveCapture(Sport.NBA, "yahoo", DAY_1, "redraft", List.of(new BoardRepository.CaptureRow(a, 1.0, 80.0, 1.0)), List.of());

        // name is NOT NULL: the unmatched insert fails after the snapshot rows were already replaced.
        assertThrows(Exception.class, () -> boards.saveCapture(Sport.NBA, "yahoo", DAY_1, "redraft",
                List.of(new BoardRepository.CaptureRow(b, 5.0, 40.0, 0.8)),
                List.of(new BoardRepository.UnmatchedRow(null, null, null, 9.0, null, null))));

        var latest = boards.latestCaptureDetail(Sport.NBA, "yahoo").orElseThrow();
        assertEquals(1, latest.rows().size());
        assertEquals(a, latest.rows().get(0).playerId(), "one transaction: the old row is still there, not the half-written new one");
    }

    @Test
    void aSourceWithNoCaptureIsEmptyNotAnEmptyCapture() {
        assertTrue(boards.latestCaptureDetail(Sport.NBA, "it046-no-such-source").isEmpty());
    }
}
