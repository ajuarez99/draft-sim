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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for DraftRepository.allWithLeagueFor -- the scoped
 * query claude/user-identity-and-onboarding.md §4b describes, same convention
 * as DraftRepositoryAllWithLeagueIT.
 */
@SpringBootTest
class DraftRepositoryAllWithLeagueForIT {

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

    private long managerA;
    private long managerB;
    private long predecessorLeagueId;
    private long rosterSeasonLeagueId;
    private long slotOnlyLeagueId;
    private long unrelatedLeagueId;
    private long predecessorDraftId;
    private long rosterSeasonDraftId;
    private long slotOnlyDraftId;
    private long unrelatedDraftId;

    @BeforeEach
    void setUp() {
        // Wipe up front in case a previous run was interrupted before tearDown ran.
        jdbc.update("delete from league where sleeper_id like ?", "it-league-scope-%");
        jdbc.update("delete from manager where sleeper_user_id like ?", "it-user-scope-%");

        managerA = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-scope-a", "Scope Test A");
        managerB = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-scope-b", "Scope Test B (unrelated)");

        predecessorLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2025, "it-league-scope-predecessor", "Predecessor Season", 12);
        rosterSeasonLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters) "
                        + "values (?, ?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-scope-roster", "it-league-scope-predecessor",
                "Roster-Season Member", 12);
        slotOnlyLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-scope-slot-only", "Slot-Only Member", 12);
        unrelatedLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-scope-unrelated", "Neither Path", 12);

        predecessorDraftId = drafts.upsert(predecessorLeagueId, "it-draft-scope-predecessor", 2025, 15, 12,
                "snake", "complete", java.time.Instant.parse("2025-08-01T00:00:00Z"), "{}");
        rosterSeasonDraftId = drafts.upsert(rosterSeasonLeagueId, "it-draft-scope-roster", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-01T00:00:00Z"), "{}");
        slotOnlyDraftId = drafts.upsert(slotOnlyLeagueId, "it-draft-scope-slot-only", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-02T00:00:00Z"),
                "{\"1\": " + managerA + "}");
        unrelatedDraftId = drafts.upsert(unrelatedLeagueId, "it-draft-scope-unrelated", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-03T00:00:00Z"), "{}");

        // managerA is a member of rosterSeasonLeagueId only via roster_season (no
        // slot_to_manager entry there) -- the path allWithLeague() alone would miss.
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, ?)",
                rosterSeasonLeagueId, managerA, 1);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id like ?", "it-league-scope-%"); // cascades draft, draft_pick, roster_season
        jdbc.update("delete from manager where sleeper_user_id like ?", "it-user-scope-%");
    }

    @Test
    void includesALeagueTheUserIsInOnlyViaRosterSeason() {
        Set<Long> ids = idsFor("it-user-scope-a");
        assertTrue(ids.contains(rosterSeasonDraftId), "roster_season membership alone must be enough");
    }

    @Test
    void includesALeagueTheUserIsInOnlyViaSlotToManager() {
        Set<Long> ids = idsFor("it-user-scope-a");
        assertTrue(ids.contains(slotOnlyDraftId), "slot_to_manager membership alone must be enough");
    }

    @Test
    void walksThePreviousLeagueIdChainBackwardsToIncludeThePredecessorSeason() {
        Set<Long> ids = idsFor("it-user-scope-a");
        assertTrue(ids.contains(predecessorDraftId),
                "a predecessor season reachable only via previous_league_id, with no membership row "
                        + "of its own, must still be included -- the chain trap");
    }

    @Test
    void excludesALeagueTheUserIsInByNeitherPath() {
        Set<Long> ids = idsFor("it-user-scope-a");
        assertFalse(ids.contains(unrelatedDraftId), "no roster_season row and no slot_to_manager entry -- not theirs");
    }

    @Test
    void unrelatedManagerSeesNoneOfTheseFourDrafts() {
        Set<Long> ids = idsFor("it-user-scope-b");
        assertFalse(ids.contains(rosterSeasonDraftId));
        assertFalse(ids.contains(slotOnlyDraftId));
        assertFalse(ids.contains(predecessorDraftId));
        assertFalse(ids.contains(unrelatedDraftId));
    }

    @Test
    void unknownSleeperUserIdYieldsEmptyNotTheUnfilteredList() {
        // The failure mode that matters most: a null-id bug that silently falls
        // through to allWithLeague()'s everything would look like working
        // software to the one person who happens to be in every league. With no
        // matching manager row, `me` is empty and every downstream CTE is empty
        // too -- this must be genuinely [], not just missing our four fixtures,
        // regardless of whatever other leagues already exist in this shared DB.
        List<DraftRepository.DraftSummary> result = drafts.allWithLeagueFor("it-user-scope-does-not-exist");
        assertTrue(result.isEmpty(), "unknown user id must resolve to no leagues at all");
    }

    private Set<Long> idsFor(String sleeperUserId) {
        return drafts.allWithLeagueFor(sleeperUserId).stream()
                .map(DraftRepository.DraftSummary::id)
                .collect(Collectors.toSet());
    }
}
