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
 * The reversal-round override (multi-sport-and-rebrand.md Phase 6b) exists for
 * one reason and this test is that reason: {@code reversal_round} is Sleeper's
 * value and {@link DraftRepository#upsert} rewrites it on every ingest, so an
 * edit made in place would be reverted by one press of the picker's "Add a
 * draft" button. That is the shape of bug that ate {@code adp_at_time} once
 * already, and it does not show up in any unit test -- it needs a real upsert
 * against a real row.
 *
 * Same convention as the other repository ITs here: real Postgres at
 * application.yml's local-dev default, SKIPPED rather than failed when it is
 * unreachable.
 */
@SpringBootTest
class DraftRepositoryReversalOverrideIT {

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

    private static final String SLEEPER_ID = "it-draft-reversal-override";
    private long leagueId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id = ?", "it-league-reversal-override");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nba", 2026, "it-league-reversal-override", "IT Reversal League", 12);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where id = ?", leagueId);
    }

    private long ingest(int reversalRoundFromSleeper) {
        return drafts.upsert(leagueId, SLEEPER_ID, 2026, 14, 12, "snake", "pre_draft",
                java.time.Instant.parse("2026-10-01T00:00:00Z"), "{}", reversalRoundFromSleeper);
    }

    @Test
    void withNoOverrideTheEffectiveValueIsSleepers() {
        ingest(3);

        DraftRepository.DraftRow row = drafts.bySleeperId(SLEEPER_ID).orElseThrow();
        assertEquals(3, row.reversalRound());

        DraftRepository.ReversalRound pair = drafts.reversalRound(row.id()).orElseThrow();
        assertEquals(3, pair.fromSleeper());
        assertNull(pair.override(), "a freshly ingested draft has no user opinion on it");
        assertEquals(3, pair.effective());
    }

    @Test
    void anOverrideWinsOverSleeperAndSurvivesAReIngest() {
        long draftId = ingest(3);

        // The user disagrees: this draft does not reverse at all.
        drafts.setReversalRoundOverride(draftId, 0);
        assertEquals(0, drafts.bySleeperId(SLEEPER_ID).orElseThrow().reversalRound());

        // Re-ingest, exactly as pressing "Add a draft" does. Sleeper still says
        // 3. Before the override column existed, this line reverted the edit.
        ingest(3);

        DraftRepository.ReversalRound pair = drafts.reversalRound(draftId).orElseThrow();
        assertEquals(3, pair.fromSleeper(), "Sleeper's own value is still tracked, not clobbered by the override");
        assertEquals(0, pair.override());
        assertEquals(0, drafts.bySleeperId(SLEEPER_ID).orElseThrow().reversalRound(),
                "the user's value must survive an ingest -- this is the whole point of the second column");
    }

    @Test
    void pinningSleepersCurrentValueIsNotTheSameAsFollowingIt() {
        long draftId = ingest(3);
        drafts.setReversalRoundOverride(draftId, 3);

        // Sleeper changes its mind (a league setting edited before draft night).
        ingest(0);

        assertEquals(3, drafts.bySleeperId(SLEEPER_ID).orElseThrow().reversalRound(),
                "a pinned value holds even when it now differs from Sleeper");
        assertEquals(0, drafts.reversalRound(draftId).orElseThrow().fromSleeper());
    }

    @Test
    void sportOfReadsTheLeaguesSportThroughTheJoin() {
        // The league this IT seeds is nba, and that is the point: LiveDraftPoller
        // resolves its player-id map through this method, and before it existed
        // the poller assumed football and wrote a whole draft of null picks
        // (claude/merge-review-multi-sport.md B2). A join that silently returned
        // the wrong sport would be worse than the bug it replaced -- 753 sleeper
        // ids exist in both sports.
        long draftId = ingest(3);
        assertEquals(com.ballknowers.draftsim.domain.Sport.NBA, drafts.sportOf(draftId).orElseThrow());
        assertTrue(drafts.sportOf(-1L).isEmpty(), "a draft that does not exist has no sport");
    }

    @Test
    void clearingTheOverrideGoesBackToFollowingSleeper() {
        long draftId = ingest(3);
        drafts.setReversalRoundOverride(draftId, 0);
        drafts.setReversalRoundOverride(draftId, null);

        DraftRepository.ReversalRound pair = drafts.reversalRound(draftId).orElseThrow();
        assertNull(pair.override());
        assertEquals(3, pair.effective());
        assertEquals(3, drafts.bySleeperId(SLEEPER_ID).orElseThrow().reversalRound());
    }
}
