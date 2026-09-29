package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.api.LeagueController;
import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for POST /api/drafts/{id}/picks' guards
 * (claude/audit-2026-09-28/03): the status gate, the duplicate-player guard, and
 * that a refused write leaves draft_pick (and so allCompletedPicks) untouched.
 * Runs as the operator (admin token, no identity) -- authorization has its own
 * tests; this is about what the endpoint will and won't write.
 */
@SpringBootTest
class ManualPickGuardsIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL);
    }

    @Autowired private LeagueController controller;
    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long managerId;
    private long playerA;
    private long playerB;
    private long draftId;
    private static final String DRAFT = "it-draft-manual-pick-guards";

    @BeforeEach
    void setUp() {
        cleanUp();
        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-mpg", "IT Manager");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-mpg", "IT League", 12);
        playerA = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', 'it-mpg-a', 'Player A', '{RB}') returning id",
                Long.class);
        playerB = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', 'it-mpg-b', 'Player B', '{WR}') returning id",
                Long.class);
        draftId = insertDraft("drafting");
    }

    private long insertDraft(String status) {
        return drafts.upsert(leagueId, DRAFT, 2026, 15, 12, "snake", status, null,
                "{\"1\": " + managerId + ", \"2\": " + managerId + "}");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from league where sleeper_id = ?", "it-league-mpg"); // cascades draft, draft_pick
        jdbc.update("delete from manager where sleeper_user_id = ?", "it-user-mpg");
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id in ('it-mpg-a', 'it-mpg-b')");
    }

    private ResponseEntity<?> post(int pickNo, String player) {
        try (var admin = TestAdmin.asAdmin()) {
            return controller.recordPick(DRAFT, new LeagueController.ManualPick(pickNo, player), null);
        }
    }

    private void setStatus(String status) {
        jdbc.update("update draft set status = ? where id = ?", status, draftId);
    }

    @Test
    void aCompleteDraftIs409AndDraftPickIsUnchanged() {
        assertEquals(200, post(1, "it-mpg-a").getStatusCode().value());
        List<DraftRepository.PickRow> before = drafts.picks(draftId);
        setStatus("complete");

        ResponseEntity<?> refused = post(2, "it-mpg-b");
        ResponseEntity<?> overwrite = post(1, "it-mpg-b");

        assertEquals(409, refused.getStatusCode().value());
        assertEquals(409, overwrite.getStatusCode().value());
        assertEquals(before, drafts.picks(draftId), "refused writes must leave every row as it was");
        assertTrue(drafts.allCompletedPicks(Sport.NFL).stream().noneMatch(p -> p.draftId() == draftId
                        && p.playerId() != null && p.playerId() == playerB),
                "the refused player must not have reached the fitting input");
    }

    @Test
    void aPreDraftDraftIs409() {
        setStatus("pre_draft");
        assertEquals(409, post(1, "it-mpg-a").getStatusCode().value());
        assertTrue(drafts.picks(draftId).isEmpty());
    }

    @Test
    void aDuplicatePlayerAtAnotherPickIs409AndNoSecondRowIsWritten() {
        assertEquals(200, post(1, "it-mpg-a").getStatusCode().value());

        ResponseEntity<?> dup = post(2, "it-mpg-a");

        assertEquals(409, dup.getStatusCode().value());
        assertTrue(dup.getBody().toString().contains("already pick 1"), String.valueOf(dup.getBody()));
        assertEquals(1, drafts.picks(draftId).size());
    }

    @Test
    void theSamePlayerAtTheSamePickIsAnIdempotent200AndACorrectionOfThePickToo() {
        assertEquals(200, post(1, "it-mpg-a").getStatusCode().value());
        assertEquals(200, post(1, "it-mpg-a").getStatusCode().value());
        assertEquals(1, drafts.picks(draftId).size());

        // Overwriting a pick with a different player is still a correction, not a duplicate.
        assertEquals(200, post(1, "it-mpg-b").getStatusCode().value());
        assertEquals(playerB, drafts.picks(draftId).get(0).playerId());
        // ...and it frees player A to be recorded elsewhere.
        assertEquals(200, post(2, "it-mpg-a").getStatusCode().value());
    }

    @Test
    void anAdminWithNoIdentityMayRecordOnADraftingDraft() {
        assertEquals(200, post(2, "it-mpg-b").getStatusCode().value());
        assertEquals(1, drafts.picks(draftId).size());
    }

    @Test
    void reversalRoundIs409OnACompleteDraftAndLeavesTheOverrideAlone() {
        setStatus("complete");
        ResponseEntity<?> response;
        try (var admin = TestAdmin.asAdmin()) {
            response = controller.setReversalRound(DRAFT, new LeagueController.ReversalRoundBody(3), null);
        }
        assertEquals(409, response.getStatusCode().value());
        assertNull(drafts.reversalRound(draftId).orElseThrow().override());
    }
}
