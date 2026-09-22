package com.ballknowers.draftsim.engine;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HeadToHeadService} against real Postgres, specs/006-deeper-history-both-sports
 * T046/T047 (US4). Like {@code ManagerCareerServiceTest}, this is a
 * repository-walking service that seeds its own small, controlled fixture
 * rather than mocking every repository call.
 *
 * <p>The conservation fixture ({@code leagueConservation}) has 4 rosters and
 * one fully-paired, fully-scored week: roster 1 (managerA) beats roster 2
 * (managerB) 100-80, and roster 3 (managerC) beats roster 4 (managerD) 95-60.
 * managerA/managerC never play each other -- the schedule only pairs 1v2 and
 * 3v4 -- which is deliberate: it exercises the "shared a season, never
 * scheduled against each other" exclusion reason alongside the conservation
 * check itself.
 */
@SpringBootTest
class HeadToHeadServiceTest {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping head-to-head test");
    }

    @Autowired private HeadToHeadService headToHead;
    @Autowired private JdbcTemplate jdbc;

    private long leagueConservation; // 2025, 4 rosters: 1v2 and 3v4 paired and scored, one week.
    private long leagueTie;          // 2025, 2 rosters, one scored week, EQUAL points.

    private long managerA; // leagueConservation roster 1 (beats B 100-80).
    private long managerB; // leagueConservation roster 2.
    private long managerC; // leagueConservation roster 3 (beats D 95-60). Never plays A.
    private long managerD; // leagueConservation roster 4.
    private long managerE; // leagueTie roster 1 (75.00, ties F).
    private long managerF; // leagueTie roster 2 (75.00, ties E).
    private long managerG; // leagueOnlyG roster 1 -- no league in common with H at all.
    private long managerH; // leagueOnlyH roster 1.

    private static final List<String> SLEEPER_USER_IDS = List.of(
            "it-h2h-a", "it-h2h-b", "it-h2h-c", "it-h2h-d",
            "it-h2h-e", "it-h2h-f", "it-h2h-g", "it-h2h-h");
    private static final List<String> LEAGUE_SLEEPER_IDS = List.of(
            "it-h2h-conservation", "it-h2h-tie", "it-h2h-onlyg", "it-h2h-onlyh");

    @BeforeEach
    void setUp() {
        cleanup();

        managerA = insertManager("it-h2h-a", "IT H2H A");
        managerB = insertManager("it-h2h-b", "IT H2H B");
        managerC = insertManager("it-h2h-c", "IT H2H C");
        managerD = insertManager("it-h2h-d", "IT H2H D");
        managerE = insertManager("it-h2h-e", "IT H2H E");
        managerF = insertManager("it-h2h-f", "IT H2H F");
        managerG = insertManager("it-h2h-g", "IT H2H G");
        managerH = insertManager("it-h2h-h", "IT H2H H");

        // --- leagueConservation: 4 rosters, one fully-paired scored week ---
        leagueConservation = insertLeague("it-h2h-conservation", 2025, 4);
        insertRosterSeason(leagueConservation, managerA, 1);
        insertRosterSeason(leagueConservation, managerB, 2);
        insertRosterSeason(leagueConservation, managerC, 3);
        insertRosterSeason(leagueConservation, managerD, 4);
        insertWeekPoints(leagueConservation, 2025, 1, 1, 100.00);
        insertWeekPoints(leagueConservation, 2025, 1, 2, 80.00);
        insertWeekPoints(leagueConservation, 2025, 1, 3, 95.00);
        insertWeekPoints(leagueConservation, 2025, 1, 4, 60.00);
        insertMatchup(leagueConservation, 2025, 1, 1, 100);
        insertMatchup(leagueConservation, 2025, 1, 2, 100);
        insertMatchup(leagueConservation, 2025, 1, 3, 101);
        insertMatchup(leagueConservation, 2025, 1, 4, 101);

        // --- leagueTie: 2 rosters, one scored week, equal points ---
        leagueTie = insertLeague("it-h2h-tie", 2025, 2);
        insertRosterSeason(leagueTie, managerE, 1);
        insertRosterSeason(leagueTie, managerF, 2);
        insertWeekPoints(leagueTie, 2025, 1, 1, 75.00);
        insertWeekPoints(leagueTie, 2025, 1, 2, 75.00);
        insertMatchup(leagueTie, 2025, 1, 1, 200);
        insertMatchup(leagueTie, 2025, 1, 2, 200);

        // --- leagueOnlyG / leagueOnlyH: managerG and managerH never share a league ---
        long leagueOnlyG = insertLeague("it-h2h-onlyg", 2025, 1);
        insertRosterSeason(leagueOnlyG, managerG, 1);
        long leagueOnlyH = insertLeague("it-h2h-onlyh", 2025, 1);
        insertRosterSeason(leagueOnlyH, managerH, 1);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("delete from league where sleeper_id in (?, ?, ?, ?)", LEAGUE_SLEEPER_IDS.toArray());
        jdbc.update("delete from manager where sleeper_user_id in (?, ?, ?, ?, ?, ?, ?, ?)",
                SLEEPER_USER_IDS.toArray());
    }

    private long insertManager(String sleeperUserId, String displayName) {
        return jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, displayName);
    }

    private long insertLeague(String sleeperId, int season, int totalRosters) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions)
                values ('nfl', ?, ?, ?, ?, 'complete', '{QB}')
                returning id
                """, Long.class, season, sleeperId, "IT League " + sleeperId, totalRosters);
    }

    private void insertRosterSeason(long leagueId, long managerId, int rosterId) {
        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for)
                values (?, ?, ?, 0, 0, 0)
                """, leagueId, managerId, rosterId);
    }

    private void insertWeekPoints(long leagueId, int season, int week, int rosterId, double points) {
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, ?, ?, ?, ?, '{}'::jsonb)
                """, leagueId, season, week, rosterId, points);
    }

    private void insertMatchup(long leagueId, int season, int week, int rosterId, int matchupId) {
        jdbc.update("""
                insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                values (?, ?, ?, ?, ?)
                """, leagueId, season, week, rosterId, matchupId);
    }

    private HeadToHeadService.SportHeadToHead nfl(HeadToHeadService.Result result) {
        return result.sports().stream()
                .filter(s -> s.sport() == Sport.NFL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no nfl SportHeadToHead in " + result));
    }

    // ------------------------------------------------------------- T046 (SC-006)

    /**
     * The conservation check, quoted from data-model.md: summed across every
     * pair of managers in a season, meetings must equal that season's paired
     * fixture count. leagueConservation stores 4 roster-week rows (2 paired
     * games: 1v2 and 3v4), so meetings summed across every pair among
     * managerA..D must equal 4/2 = 2 -- one meeting on the A-B pair, one on
     * C-D, and zero everywhere else (A-C, A-D, B-C, B-D never played).
     *
     * <p>A join that double-counts a pairing (returns it from both sides) or
     * drops one (an off-by-one in the self-join's {@code roster_id >}) fails
     * this by producing a sum other than 2.
     */
    @Test
    void meetingsConserveAcrossEveryManagerPairInASeason() {
        long[] managers = {managerA, managerB, managerC, managerD};
        int totalMeetings = 0;
        for (int i = 0; i < managers.length; i++) {
            for (int j = i + 1; j < managers.length; j++) {
                HeadToHeadService.Result r = headToHead.compute(managers[i], managers[j]);
                assertFalse(r.sharedNothing(), managers[i] + " and " + managers[j] + " share leagueConservation");
                totalMeetings += nfl(r).meetings().size();
            }
        }
        assertEquals(2, totalMeetings, "4 paired roster-week rows / 2 = 2 meetings, conserved across every pair");
    }

    @Test
    void theABPairingIsScoredCorrectly() {
        HeadToHeadService.Result r = headToHead.compute(managerA, managerB);
        HeadToHeadService.SportHeadToHead nfl = nfl(r);
        assertEquals(1, nfl.aWins());
        assertEquals(0, nfl.bWins());
        assertEquals(0, nfl.ties());
        assertEquals(1, nfl.meetings().size());
        HeadToHeadService.Meeting m = nfl.meetings().get(0);
        assertEquals("A", m.winner());
        assertEquals(100.00, m.aPoints(), 1e-9);
        assertEquals(80.00, m.bPoints(), 1e-9);
    }

    /**
     * managerA and managerC share leagueConservation (both own a roster in
     * it) but the schedule only ever paired 1v2 and 3v4 -- they never played
     * each other. This must surface as a shared sport entry with an empty
     * meetings list and a named reason, not as sharedNothing (they DID share
     * a season) and not as a silent absence (US4.4).
     */
    @Test
    void aSharedSeasonThatNeverPairedThemIsNamedInSeasonsExcluded() {
        HeadToHeadService.Result r = headToHead.compute(managerA, managerC);
        assertFalse(r.sharedNothing());
        HeadToHeadService.SportHeadToHead nfl = nfl(r);
        assertEquals(0, nfl.aWins());
        assertEquals(0, nfl.bWins());
        assertEquals(0, nfl.ties());
        assertTrue(nfl.meetings().isEmpty());
        assertEquals(1, nfl.seasonsExcluded().size());
        assertEquals(2025, nfl.seasonsExcluded().get(0).season());
        assertEquals("the schedule never paired them this season", nfl.seasonsExcluded().get(0).reason());
    }

    // ------------------------------------------------------------- T047(a)

    /** US4.5: a tie is counted as a tie, never folded into either side's losses. */
    @Test
    void aTieIsCountedAsATieNeverFoldedIntoLosses() {
        HeadToHeadService.Result r = headToHead.compute(managerE, managerF);
        assertFalse(r.sharedNothing());
        HeadToHeadService.SportHeadToHead nfl = nfl(r);
        assertEquals(0, nfl.aWins());
        assertEquals(0, nfl.bWins());
        assertEquals(1, nfl.ties());
        assertEquals(1, nfl.meetings().size());
        assertEquals("TIE", nfl.meetings().get(0).winner());
    }

    // ------------------------------------------------------------- T047(b)

    /**
     * US4.3: two managers who have never shared a league return an empty
     * sports[] with sharedNothing: true -- never a 0-0 record, which would be
     * indistinguishable from "they played and split every game evenly".
     */
    @Test
    void twoManagersWhoNeverSharedALeagueReturnSharedNothing() {
        HeadToHeadService.Result r = headToHead.compute(managerG, managerH);
        assertTrue(r.sharedNothing());
        assertTrue(r.sports().isEmpty());
    }
}
