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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for DraftRepository.upsertPicks, per claude/live-poller-plan.md's
 * "Integration (real Postgres)" section: no Testcontainers/embedded-DB harness exists in
 * this repo, so this reuses the real DraftRepository bean + Flyway-migrated schema against
 * application.yml's local-dev default (localhost:5433/draftsim). Gated so the suite SKIPS
 * (not fails) when that Postgres isn't reachable.
 */
@SpringBootTest
class DraftRepositoryUpsertPicksIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping DraftRepository integration test");
    }

    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long managerId;
    private long managerId2;
    private long draftId;

    @BeforeEach
    void setUp() {
        // Fixture rows scoped under distinct "it-" sleeper ids; wiped up front in case a
        // previous run was interrupted before tearDown ran.
        jdbc.update("delete from league where sleeper_id = ?", "it-league-upsert-picks");
        jdbc.update("delete from manager where sleeper_user_id in (?, ?)", "it-user-1", "it-user-2");

        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-1", "IT Manager 1");
        managerId2 = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-2", "IT Manager 2");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-upsert-picks", "IT League", 14);

        draftId = drafts.upsert(leagueId, "it-draft-upsert-picks", 2026, 15, 14, "snake", "drafting", null, "{}");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id = ?", leagueId);           // cascades draft, draft_pick
        jdbc.update("delete from manager where id in (?, ?)", managerId, managerId2);
    }

    @Test
    void upsertPicksCalledTwiceWithIdenticalRowsStaysIdempotent() {
        List<DraftRepository.PickRow> rows = List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId, null, null),
                new DraftRepository.PickRow(draftId, 2, 1, 2, managerId2, null, null));

        drafts.upsertPicks(draftId, rows);
        drafts.upsertPicks(draftId, rows);   // same tick data reapplied, as the poller does every 10s

        assertEquals(2, drafts.picks(draftId).size(), "re-upserting identical rows must not duplicate them");
    }

    @Test
    void adpAtTimeIsCoalescedNotClobberedByALaterPollTick() {
        List<DraftRepository.PickRow> rows = List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId, null, null));
        drafts.upsertPicks(draftId, rows);

        // Simulate /api/ingest/board's backfill writing a real adp_at_time onto this pick.
        jdbc.update("update draft_pick set adp_at_time = ? where draft_id = ? and pick_no = ?",
                42.5, draftId, 1);

        // The poller ticks again with the same still-null-adp row -- this must NOT null the backfill out.
        drafts.upsertPicks(draftId, rows);

        Double adpAtTime = drafts.picks(draftId).get(0).adpAtTime();
        assertNotNull(adpAtTime, "adp_at_time must not be clobbered back to null on a later poll tick");
        assertEquals(42.5, adpAtTime, 0.001);
    }

    @Test
    void upsertPicksWithChangedManagerIdUpdatesInPlace() {
        List<DraftRepository.PickRow> initial = List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId, null, null));
        drafts.upsertPicks(draftId, initial);

        // A late-resolved picked_by, or a draft-day trade reassigning the slot.
        List<DraftRepository.PickRow> resolved = List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId2, null, null));
        drafts.upsertPicks(draftId, resolved);

        List<DraftRepository.PickRow> result = drafts.picks(draftId);
        assertEquals(1, result.size(), "count must stay unchanged on an in-place update");
        assertEquals(managerId2, result.get(0).managerId());
    }

    /**
     * HANDOFF's "Known live bug", now closed. replacePicks (the league-ingest path,
     * one click of the UI's "Add a draft" button) deleted every pick and reinserted
     * it binding PickMapper's hardcoded null adp_at_time -- so any ingest run after
     * a board rebuild silently zeroed the contemporaneous board position on every
     * pick, and with it every fitted manager profile, while /api/managers went on
     * returning full-looking output.
     */
    @Test
    void replacePicksPreservesABackfilledAdpAtTime() {
        List<DraftRepository.PickRow> rows = List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId, null, null),
                new DraftRepository.PickRow(draftId, 2, 1, 2, managerId2, null, null));
        drafts.replacePicks(draftId, rows);

        // BoardService.backfillAdpAtTime, at the end of a board rebuild.
        jdbc.update("update draft_pick set adp_at_time = ? where draft_id = ?", 17.5, draftId);

        // A second league ingest of the same draft -- identical picks, still null adp.
        drafts.replacePicks(draftId, rows);

        List<DraftRepository.PickRow> after = drafts.picks(draftId);
        assertEquals(2, after.size());
        for (DraftRepository.PickRow p : after) {
            assertNotNull(p.adpAtTime(), "pick " + p.pickNo() + " lost its adp_at_time on re-ingest");
            assertEquals(17.5, p.adpAtTime(), 0.001);
        }
    }

    /**
     * replacePicks is still a *replace*: a pick that is no longer in the incoming
     * list has to go. The rewrite swapped "delete everything then insert" for
     * "delete what is missing then upsert", so this pins the half that could
     * regress into an append-only upsert.
     */
    @Test
    void replacePicksStillDropsPicksMissingFromTheIncomingList() {
        drafts.replacePicks(draftId, List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId, null, null),
                new DraftRepository.PickRow(draftId, 2, 1, 2, managerId2, null, null)));

        drafts.replacePicks(draftId, List.of(
                new DraftRepository.PickRow(draftId, 2, 1, 2, managerId2, null, null)));

        List<DraftRepository.PickRow> after = drafts.picks(draftId);
        assertEquals(1, after.size(), "pick 1 was not in the incoming list and should be gone");
        assertEquals(2, after.get(0).pickNo());

        drafts.replacePicks(draftId, List.of());
        assertTrue(drafts.picks(draftId).isEmpty(), "an empty incoming list must clear the draft's picks");
    }

    /**
     * Regression guard, pinning behavior already true today (DraftRepository.java's
     * "where d.status = 'complete'" clause): a pick under a non-complete draft must never
     * leak into allCompletedPicks(), which feeds ProfileService.fit(). This draft's status
     * is "drafting" (set in setUp), so its picks must be excluded.
     */
    @Test
    void allCompletedPicksExcludesPicksFromANonCompleteDraft() {
        List<DraftRepository.PickRow> rows = List.of(
                new DraftRepository.PickRow(draftId, 1, 1, 1, managerId, null, null));
        drafts.upsertPicks(draftId, rows);

        boolean leaked = drafts.allCompletedPicks().stream().anyMatch(p -> p.draftId() == draftId);
        assertFalse(leaked, "a pick under a 'drafting' draft must not appear in allCompletedPicks()");
    }
}
