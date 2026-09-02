package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ProfileService;
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
 * The standing regression guard claude/next-features-roadmap.md §5 calls for:
 * "these are different table names" is the ONLY thing stopping a mock session
 * from contaminating fitted manager profiles, so this must stay green through
 * any future refactor of ingestion or the mock schema, not be treated as a
 * one-time check-the-box item (see also §2a).
 *
 * Uses a manager id that is BOTH a real manager with real completed-draft
 * history AND the manager_id recorded on a mock pick in the same session --
 * the actual scenario that would expose a leak, since a leak that only shows
 * up for a manager with no other data would be easy to miss.
 */
@SpringBootTest
class MockDraftContaminationIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping mock-contamination integration test");
    }

    @Autowired private DraftRepository drafts;
    @Autowired private MockDraftRepository mockDrafts;
    @Autowired private ProfileService profiles;
    @Autowired private JdbcTemplate jdbc;

    private long leagueId;
    private long managerId;
    private long realDraftId;
    private long playerId;
    private long mockSessionId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id = ?", "it-league-mock-contamination");
        jdbc.update("delete from manager where sleeper_user_id = ?", "it-user-mock-contamination");
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id = ?", "it-player-mock-contamination");

        managerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-mock-contamination", "IT Manager");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-mock-contamination", "IT League", 8);
        playerId = jdbc.queryForObject(
                "insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{RB}') returning id",
                Long.class, "it-player-mock-contamination", "IT Player");

        // Real, completed draft: this manager has genuine history, so the guard
        // below is checking "unaffected by the mock rows," not "no data at all."
        realDraftId = drafts.upsert(leagueId, "it-draft-mock-contamination", 2026, 15, 8,
                "snake", "complete", null, "{}");
        drafts.upsertPicks(realDraftId, List.of(
                new DraftRepository.PickRow(realDraftId, 1, 1, 1, managerId, playerId, 1.0)));

        // A mock session, with the SAME manager id recorded on one of its picks
        // (a MANAGER seat) -- the scenario that would expose a leak if
        // allCompletedPicks()/fit() ever started reading mock_draft_pick.
        mockSessionId = mockDrafts.createSession(8, 15, List.of("QB", "BN"), 1.0,
                "[{\"slot\":2,\"type\":\"MANAGER\",\"managerId\":" + managerId + "}]", 1, 99L);
        mockDrafts.insertPicks(mockSessionId, List.of(
                new MockDraftRepository.PickRow(mockSessionId, 2, 1, 2, "MANAGER", managerId, playerId, "BOT")));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from mock_draft_session where id = ?", mockSessionId);
        jdbc.update("delete from league where id = ?", leagueId);   // cascades draft, draft_pick
        jdbc.update("delete from manager where id = ?", managerId);
        jdbc.update("delete from player where id = ?", playerId);
    }

    @Test
    void allCompletedPicksNeverIncludesAMockDraftPickRow() {
        boolean leaked = drafts.allCompletedPicks().stream()
                .anyMatch(p -> p.playerId() != null && p.playerId() == playerId && p.draftId() != realDraftId);
        assertFalse(leaked, "allCompletedPicks() must never surface a row that did not come from draft_pick");

        long fromRealDraftOnly = drafts.allCompletedPicks().stream()
                .filter(p -> p.managerId() != null && p.managerId() == managerId)
                .count();
        assertEquals(1, fromRealDraftOnly,
                "this manager must show exactly the one real completed pick, not a second one from the mock session");
    }

    @Test
    void fittingProfilesIsUnaffectedByTheMockSessionExisting() {
        ProfileService.Fit withMockSession = profiles.fit(Sport.NFL);

        jdbc.update("delete from mock_draft_session where id = ?", mockSessionId);
        ProfileService.Fit withoutMockSession = profiles.fit(Sport.NFL);

        assertEquals(withoutMockSession.scoreablePicks(), withMockSession.scoreablePicks(),
                "the mock session's pick must not count toward scoreablePicks");
        assertEquals(withoutMockSession.profiles().get(managerId).picksScored(),
                withMockSession.profiles().get(managerId).picksScored(),
                "the mock session's pick must not be counted against this manager's fitted profile");
        // The session was already deleted above; tearDown()'s own delete for
        // mockSessionId is then simply a no-op (0 rows), which is fine.
    }
}
