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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres coverage for the membership rule now that more than one caller
 * depends on it. Fixture mirrors DraftRepositoryAllWithLeagueForIT's on purpose:
 * the last test in this class asserts the two agree, which is the property that
 * matters once {@link DraftRepository#allWithLeagueFor} and
 * {@link LeagueMembership#canSee} are what stands between a visitor and someone
 * else's league.
 */
@SpringBootTest
class LeagueMembershipIT {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping LeagueMembership integration test");
    }

    @Autowired private LeagueMembership membership;
    @Autowired private DraftRepository drafts;
    @Autowired private JdbcTemplate jdbc;

    private long managerA;
    private long managerB;
    private long predecessorLeagueId;
    private long rosterSeasonLeagueId;
    private long slotOnlyLeagueId;
    private long unrelatedLeagueId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from league where sleeper_id like ?", "it-league-mem-%");
        jdbc.update("delete from manager where sleeper_user_id like ?", "it-user-mem-%");

        managerA = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-mem-a", "Membership A");
        managerB = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-user-mem-b", "Membership B (unrelated)");

        predecessorLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2025, "it-league-mem-predecessor", "Predecessor Season", 12);
        rosterSeasonLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, previous_league_id, name, total_rosters) "
                        + "values (?, ?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-mem-roster", "it-league-mem-predecessor",
                "Roster-Season Member", 12);
        slotOnlyLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-mem-slot-only", "Slot-Only Member", 12);
        unrelatedLeagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values (?, ?, ?, ?, ?) returning id",
                Long.class, "nfl", 2026, "it-league-mem-unrelated", "Neither Path", 12);

        drafts.upsert(predecessorLeagueId, "it-draft-mem-predecessor", 2025, 15, 12,
                "snake", "complete", java.time.Instant.parse("2025-08-01T00:00:00Z"), "{}");
        drafts.upsert(rosterSeasonLeagueId, "it-draft-mem-roster", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-01T00:00:00Z"), "{}");
        drafts.upsert(slotOnlyLeagueId, "it-draft-mem-slot-only", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-02T00:00:00Z"),
                "{\"1\": " + managerA + "}");
        drafts.upsert(unrelatedLeagueId, "it-draft-mem-unrelated", 2026, 15, 12,
                "snake", "pre_draft", java.time.Instant.parse("2026-08-03T00:00:00Z"),
                "{\"1\": " + managerB + "}");

        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, ?)",
                rosterSeasonLeagueId, managerA, 1);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id like ?", "it-league-mem-%");
        jdbc.update("delete from manager where sleeper_user_id like ?", "it-user-mem-%");
    }

    @Test
    void canSeeALeagueJoinedByEitherPathAndItsPredecessor() {
        assertTrue(membership.canSee("it-user-mem-a", rosterSeasonLeagueId), "roster_season membership");
        assertTrue(membership.canSee("it-user-mem-a", slotOnlyLeagueId), "slot_to_manager membership");
        assertTrue(membership.canSee("it-user-mem-a", predecessorLeagueId),
                "a predecessor season of a league you're in is still yours");
    }

    @Test
    void cannotSeeALeagueWithNoMembershipAtAll() {
        assertFalse(membership.canSee("it-user-mem-a", unrelatedLeagueId));
    }

    @Test
    void anUnknownUserSeesNothingRatherThanEverything() {
        // The failure mode that matters: a scoping bug that falls through to
        // "all leagues" looks exactly like working software to the one person
        // who happens to be in every league.
        assertFalse(membership.canSee("it-user-mem-nobody", rosterSeasonLeagueId));
        assertTrue(membership.leagueIdsFor("it-user-mem-nobody").isEmpty());
    }

    /**
     * Was noIdentityHeaderAtAllIsAllowedThrough, which pinned "the pre-identity
     * contract, kept deliberately". That contract is the bug
     * (claude/audit-2026-09-28/01): on production a header-less GET of a league's
     * analysis answered 200 while a stranger's id answered 404, so leaving the header
     * off got MORE than signing in as nobody. No identity now sees nothing, on every
     * door that goes through this class.
     */
    @Test
    void noIdentityHeaderAtAllSeesNothing() {
        for (String blank : new String[] {null, "", "  "}) {
            assertFalse(membership.canSee(blank, unrelatedLeagueId), "canSee");
            assertFalse(membership.canSee(blank, rosterSeasonLeagueId), "canSee, even a real league");
            assertFalse(membership.canSeeManager(blank, managerB), "canSeeManager");
            assertTrue(membership.visibleLeague("it-league-mem-roster", blank).isEmpty(), "visibleLeague");
            assertTrue(membership.visibleDraft(blank, "it-draft-mem-roster").isEmpty(), "visibleDraft");
        }
    }

    @Test
    void aValidAdminTokenIsTheOnlyWayAroundABlankIdentity() {
        try (var admin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            assertTrue(membership.canSee(null, unrelatedLeagueId));
            assertTrue(membership.canSeeManager(null, managerB));
            assertTrue(membership.visibleLeague("it-league-mem-roster", null).isPresent());
            assertTrue(membership.visibleDraft(null, "it-draft-mem-roster").isPresent());
        }
        try (var wrong = com.ballknowers.draftsim.TestAdmin.withToken("not-the-token")) {
            assertFalse(membership.canSee(null, unrelatedLeagueId), "a wrong token is no token");
        }
        try (var blank = com.ballknowers.draftsim.TestAdmin.withToken("")) {
            assertFalse(membership.canSee(null, unrelatedLeagueId), "a blank presented token never matches");
        }
        assertFalse(membership.canSee(null, unrelatedLeagueId), "and with no request at all, nobody is admin");
    }

    /** The token overrides only a BLANK identity; a named stranger is still a stranger. */
    @Test
    void theAdminTokenDoesNotWidenASignedInStrangersView() {
        try (var admin = com.ballknowers.draftsim.TestAdmin.asAdmin()) {
            assertFalse(membership.canSee("it-user-mem-a", unrelatedLeagueId));
        }
    }

    @Test
    void aManagerIsVisibleOnlyToSomeoneWhoSharesALeagueWithThem() {
        assertTrue(membership.canSeeManager("it-user-mem-a", managerA), "yourself, trivially");
        assertFalse(membership.canSeeManager("it-user-mem-a", managerB),
                "B plays only in a league A has nothing to do with");
        assertFalse(membership.canSeeManager("it-user-mem-b", managerA));
    }

    @Test
    void canSeeAgreesWithTheScopedDraftListItSharesItsRuleWith() {
        // The one-rule property. These two are the whole of the app's scoping,
        // and they now read from the same CTE precisely so they cannot drift --
        // this fails if someone gives either one its own copy again.
        Set<Long> visibleViaDraftList = drafts.allWithLeagueFor("it-user-mem-a").stream()
                .map(DraftRepository.DraftSummary::leagueId)
                .collect(Collectors.toSet());

        for (long leagueId : Set.of(predecessorLeagueId, rosterSeasonLeagueId,
                slotOnlyLeagueId, unrelatedLeagueId)) {
            assertEquals(visibleViaDraftList.contains(leagueId), membership.canSee("it-user-mem-a", leagueId),
                    "league " + leagueId + " must be judged the same by both");
        }
    }
}
