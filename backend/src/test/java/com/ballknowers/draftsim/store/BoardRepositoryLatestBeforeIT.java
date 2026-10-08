package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 022 T048 / T047 against the real local Postgres (AGENTS.md bug classes #2, #3, #6: SQL and binds that
 * have never run are not "working"): {@code BoardRepository.latestBefore} (a new method, whose predecessor
 * {@code asOf} has no callers) and the nullable {@code startTime} now on {@code DraftRepository.DraftRow}
 * (a {@code timestamptz} read as {@code OffsetDateTime}). SKIPS when the local Postgres or its NBA drafts are
 * absent, so the caller must read the skip count.
 *
 * <p>What the database held when this was written (2026-10-08): NBA {@code blend} captures on 2026-09-08,
 * 09-09, 09-14 and 09-28 only (553 rows on the last), none for 2025; the 2025 NBA draft started
 * 2025-10-04T19:46Z and the 2026 one is scheduled 2026-10-10T19:15:08Z. The assertions read the capture dates
 * back from the table, so a later capture changes the 2026-10-10 expectation only if it lands on or before that
 * date, and the test prints which date it found.
 */
@SpringBootTest
class BoardRepositoryLatestBeforeIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String NBA_2025 = "1229352720222134272";
    private static final String NBA_2026 = "1339351318115946496";

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

    @Autowired private BoardRepository board;
    @Autowired private DraftRepository drafts;
    @Autowired private LeagueRepository leagues;
    @Autowired private JdbcTemplate jdbc;

    private DraftRepository.DraftRow draftOf(String sleeperLeagueId) {
        Optional<LeagueRepository.LeagueRow> l = leagues.bySleeperId(sleeperLeagueId);
        Assumptions.assumeTrue(l.isPresent(), "league " + sleeperLeagueId + " is not in the local database");
        Optional<DraftRepository.DraftRow> d = drafts.forLeague(l.get().id());
        Assumptions.assumeTrue(d.isPresent(), "league " + sleeperLeagueId + " has no draft row");
        return d.get();
    }

    @Test
    void noNbaBlendCaptureIsOnOrBeforeThe2025DraftsStartDate() {
        DraftRepository.DraftRow d = draftOf(NBA_2025);
        assertNotNull(d.startTime());
        LocalDate start = d.startTime().atZone(ZoneOffset.UTC).toLocalDate();
        assertEquals(LocalDate.of(2025, 10, 4), start);
        assertTrue(board.latestBefore(Sport.NBA, BoardRepository.SOURCE_BLEND, start).isEmpty());
    }

    @Test
    void the2026DraftDateReadsTheLatestCaptureOnOrBeforeItWithItsRows() {
        LocalDate asked = LocalDate.of(2026, 10, 10);
        LocalDate expected = jdbc.queryForObject(
                "select max(captured_on) from adp_snapshot where sport = 'nba' and source = 'blend' and captured_on <= ?",
                LocalDate.class, asked);
        Assumptions.assumeTrue(expected != null, "no NBA blend capture is stored locally");
        System.out.println("REPORT latestBefore(NBA, blend, 2026-10-10) found " + expected);

        BoardRepository.Capture c = board.latestBefore(Sport.NBA, BoardRepository.SOURCE_BLEND, asked).orElseThrow();
        assertEquals(expected, c.capturedOn());
        assertEquals(LocalDate.of(2026, 9, 28), c.capturedOn(),
                "the 09-28 capture, unless a later one landed on or before 10-10");
        int stored = jdbc.queryForObject(
                "select count(*) from adp_snapshot where sport = 'nba' and source = 'blend' and captured_on = ?",
                Integer.class, expected);
        assertEquals(stored, c.rows().size());
        assertTrue(c.rows().size() > 100);
        // Never the search rank: every returned player id is a row of the blend capture itself.
        List<Long> blendIds = jdbc.queryForList(
                "select player_id from adp_snapshot where sport = 'nba' and source = 'blend' and captured_on = ?",
                Long.class, expected);
        assertEquals(blendIds.size(), c.rows().size());
        assertTrue(c.rows().stream().allMatch(r -> blendIds.contains(r.playerId())));
        for (int i = 1; i < c.rows().size(); i++) {
            assertTrue(c.rows().get(i - 1).adp() <= c.rows().get(i).adp(), "best ADP first");
        }
    }

    @Test
    void onOrBeforeIsInclusiveAndAnEarlierDateFindsAnEarlierCapture() {
        List<LocalDate> dates = jdbc.queryForList(
                "select distinct captured_on from adp_snapshot where sport = 'nba' and source = 'blend' order by 1",
                LocalDate.class);
        Assumptions.assumeTrue(dates.size() >= 2, "need two NBA blend captures");
        LocalDate first = dates.get(0);
        LocalDate second = dates.get(1);
        assertTrue(board.latestBefore(Sport.NBA, BoardRepository.SOURCE_BLEND, first.minusDays(1)).isEmpty());
        assertEquals(first, board.latestBefore(Sport.NBA, BoardRepository.SOURCE_BLEND, first).orElseThrow().capturedOn());
        assertEquals(first, board.latestBefore(Sport.NBA, BoardRepository.SOURCE_BLEND, second.minusDays(1))
                .orElseThrow().capturedOn());
        assertEquals(second, board.latestBefore(Sport.NBA, BoardRepository.SOURCE_BLEND, second).orElseThrow().capturedOn());
        // A source with no capture is empty, and the other sport's captures are not mixed in.
        assertTrue(board.latestBefore(Sport.NBA, "no-such-source", LocalDate.of(2030, 1, 1)).isEmpty());
        assertTrue(board.latestBefore(Sport.NBA, BoardRepository.SOURCE_FFC, LocalDate.of(2030, 1, 1)).isEmpty());
    }

    @Test
    void theDraftRowCarriesTheScheduledStartTimeFromBothQueries() {
        DraftRepository.DraftRow d = draftOf(NBA_2026);
        OffsetDateTime stored = jdbc.queryForObject("select start_time from draft where sleeper_draft_id = ?",
                OffsetDateTime.class, d.sleeperDraftId());
        Assumptions.assumeTrue(stored != null, "the 2026 draft has no stored start_time");
        System.out.println("REPORT 2026 NBA draft start_time = " + d.startTime());
        assertEquals(stored.toInstant(), d.startTime());
        assertEquals(Instant.parse("2026-10-10T19:15:08Z"), d.startTime());
        assertEquals(d.startTime(), drafts.bySleeperId(d.sleeperDraftId()).orElseThrow().startTime());
        assertEquals(LocalDate.of(2026, 10, 10), d.startTime().atZone(ZoneOffset.UTC).toLocalDate());
    }

    @Test
    void aDraftRowBuiltWithoutAStartTimeHasNone() {
        var d = new DraftRepository.DraftRow(1L, 1L, "x", 2026, 3, 12, "pre_draft", java.util.Map.of());
        assertNull(d.startTime());
        assertNull(new DraftRepository.DraftRow(1L, 1L, "x", 2026, 3, 12, "pre_draft", java.util.Map.of(), 3).startTime());
    }
}
