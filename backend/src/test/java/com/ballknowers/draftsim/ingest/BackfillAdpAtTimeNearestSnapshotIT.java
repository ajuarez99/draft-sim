package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.config.BoardProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * claude/audit-2026-09-28/11-reach-bias-baseline.md: adp_at_time is stamped from the ONE blend
 * snapshot nearest the draft date (earlier date on a tie), not whichever in-window row Postgres
 * happened to join.
 *
 * Runs the real UPDATE on the shared dev database, so it is fenced two ways: the fixture draft
 * sits in 2001 (no real snapshot is anywhere near it) and the call is limited to that one draft
 * row id via the package-private overload. It cannot touch real drafts or real picks
 * (lessons.md #22).
 */
@SpringBootTest
class BackfillAdpAtTimeNearestSnapshotIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";
    private static final String LEAGUE = "it-league-backfill-nearest";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL);
    }

    @Autowired private BoardService boards;
    @Autowired private BoardProperties cfg;
    @Autowired private JdbcTemplate jdbc;

    private long draftId;
    private long playerA;
    private long playerB;

    @BeforeEach
    void setUp() {
        cleanUp();
        long leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nfl', 2001, ?, 'IT', 2) returning id",
                Long.class, LEAGUE);
        draftId = jdbc.queryForObject(
                "insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, start_time, slot_to_manager)"
                        + " values (?, 'it-draft-backfill-nearest', 2001, 1, 2, 'snake', 'complete', timestamptz '2001-06-15 12:00:00+00', '{}'::jsonb) returning id",
                Long.class, leagueId);
        playerA = player("a");
        playerB = player("b");
        jdbc.update("insert into draft_pick (draft_id, pick_no, round, draft_slot, player_id, adp_at_time) values (?, 1, 1, 1, ?, 999)",
                draftId, playerA);
        jdbc.update("insert into draft_pick (draft_id, pick_no, round, draft_slot, player_id, adp_at_time) values (?, 2, 1, 2, ?, 999)",
                draftId, playerB);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from player where sleeper_id like 'it-bf-%'");
    }

    private long player(String tag) {
        return jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name) values ('nfl', ?, ?) returning id",
                Long.class, "it-bf-" + tag, "IT " + tag);
    }

    /** Snapshot on 2001-06-<day>; A ranks {@code adp}, B ranks {@code adp + 1}. */
    private void snapshot(String date, double adp) {
        jdbc.update("insert into adp_snapshot (player_id, sport, source, captured_on, adp) values (?, 'nfl', 'blend', date '" + date + "', ?)",
                playerA, adp);
        jdbc.update("insert into adp_snapshot (player_id, sport, source, captured_on, adp) values (?, 'nfl', 'blend', date '" + date + "', ?)",
                playerB, adp + 1);
    }

    private double stamped(int pickNo) {
        return jdbc.queryForObject("select adp_at_time from draft_pick where draft_id = ? and pick_no = ?",
                Double.class, draftId, pickNo);
    }

    @Test
    void theNearerSnapshotWinsWhateverTheOthersAre() {
        snapshot("2001-06-01", 10);   // 14 days before
        snapshot("2001-06-13", 20);   // 2 days before
        snapshot("2001-06-16", 30);   // 1 day after: nearest
        snapshot("2001-06-25", 40);   // 10 days after
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        assertEquals(30.0, stamped(1), 1e-9);
        assertEquals(31.0, stamped(2), 1e-9);
    }

    @Test
    void aBeforeSnapshotCanWinToo() {
        snapshot("2001-06-14", 20);   // 1 day before: nearest
        snapshot("2001-06-18", 30);   // 3 days after
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        assertEquals(20.0, stamped(1), 1e-9);
    }

    @Test
    void aTieGoesToTheEarlierDate() {
        snapshot("2001-06-13", 20);   // 2 days before
        snapshot("2001-06-17", 30);   // 2 days after
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        assertEquals(20.0, stamped(1), 1e-9);
        assertEquals(21.0, stamped(2), 1e-9);
    }

    @Test
    void aSnapshotOutsideTheWindowIsNeverUsed() {
        int lag = cfg.maxBoardLagDays();
        // One day beyond the window on each side: nothing in range, nothing stamped.
        jdbc.update("insert into adp_snapshot (player_id, sport, source, captured_on, adp) values (?, 'nfl', 'blend', date '2001-06-15' - ?, 7)",
                playerA, lag + 1);
        jdbc.update("insert into adp_snapshot (player_id, sport, source, captured_on, adp) values (?, 'nfl', 'blend', date '2001-06-15' + ?, 8)",
                playerA, lag + 1);
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        assertEquals(999.0, stamped(1), 1e-9, "no in-window snapshot: the pick is left as it was");
    }

    @Test
    void reRunningIsIdempotentAndRestampsAStaleValue() {
        snapshot("2001-06-12", 20);
        snapshot("2001-06-16", 30);
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        double first = stamped(1);
        jdbc.update("update draft_pick set adp_at_time = 555 where draft_id = ?", draftId);
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        assertEquals(30.0, first, 1e-9);
        assertEquals(first, stamped(1), 1e-9);
    }

    @Test
    void aPlayerMissingFromTheChosenSnapshotKeepsItsOldValue() {
        snapshot("2001-06-16", 30);
        jdbc.update("delete from adp_snapshot where player_id = ?", playerB);
        snapshot("2001-06-10", 12);   // farther, and it has B -- but the draft is stamped from ONE snapshot
        jdbc.update("delete from adp_snapshot where player_id = ? and captured_on = date '2001-06-10'", playerA);
        boards.backfillAdpAtTime(Sport.NFL, draftId);
        assertEquals(30.0, stamped(1), 1e-9);
        assertEquals(999.0, stamped(2), 1e-9);
    }

    @Test
    void aDraftWithNoStartTimeIsSkipped() {
        jdbc.update("update draft set start_time = null where id = ?", draftId);
        snapshot("2001-06-15", 30);
        assertEquals(0, boards.backfillAdpAtTime(Sport.NFL, draftId));
        assertEquals(999.0, stamped(1), 1e-9);
    }
}
