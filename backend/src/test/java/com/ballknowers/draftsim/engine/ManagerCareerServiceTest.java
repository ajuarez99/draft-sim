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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ManagerCareerService} against real Postgres, specs/006-deeper-history-both-sports
 * T030/T031 (US3). A repository-walking service like this one is exactly what
 * {@code LeagueRecordIT}/{@code LeagueHistoryContaminationIT} argue a Mockito
 * test cannot reach honestly, so this seeds its own small, controlled fixture
 * -- four managers sharing one 4-roster league-season -- rather than either
 * mocking every repository call or depending on the real database's own
 * numbers drifting under it.
 *
 * <p>Every roster in the fixture plays a single {@code QB} slot with exactly
 * one player, so the started lineup is trivially the optimal one: this keeps
 * {@code RosterManagementService}'s efficiency at a known, checkable 1.0
 * wherever a full breakdown is seeded, and lets a deliberately empty
 * breakdown (managerZeroPotential) isolate the "potential is 0" case cleanly.
 */
@SpringBootTest
class ManagerCareerServiceTest {

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
                "no local Postgres reachable at " + JDBC_URL + " -- skipping manager career test");
    }

    @Autowired private ManagerCareerService careers;
    @Autowired private JdbcTemplate jdbc;

    private long leagueConservation; // 2025, complete, 4 rosters, 1 scored week -- the conservation fixture.
    private long leagueUnplayed;     // 2026, in_season, 0 scored weeks.
    private long leagueZeroPotential; // 2025, complete, 1 scored week with NO per-player breakdown.

    private long managerA; // leagueConservation roster 1 (win) + leagueUnplayed roster 1 (poisoned stats).
    private long managerB; // leagueConservation roster 2 (loss).
    private long managerC; // leagueConservation roster 3 (win).
    private long managerD; // leagueConservation roster 4 (loss).
    private long managerE; // leagueUnplayed roster 2 ONLY -- no games ever played.
    private long managerF; // leagueZeroPotential roster 1 ONLY -- scored, but no breakdown.

    private long leaguePlayoffBound; // 2025, complete, 4 rosters, playoff_week_start=2 -- week 1 regular, week 2 playoff.

    private long managerG; // leaguePlayoffBound roster 1 -- winsAboveExpected must come from week 1 alone.
    private long managerH; // leaguePlayoffBound roster 2.
    private long managerI; // leaguePlayoffBound roster 3.
    private long managerJ; // leaguePlayoffBound roster 4.

    private static final List<String> SLEEPER_USER_IDS = List.of(
            "it-career-a", "it-career-b", "it-career-c", "it-career-d", "it-career-e", "it-career-f",
            "it-career-g", "it-career-h", "it-career-i", "it-career-j");
    private static final List<String> LEAGUE_SLEEPER_IDS = List.of(
            "it-career-conservation", "it-career-unplayed", "it-career-zero-potential", "it-career-playoff-bound");
    private static final List<String> PLAYER_SLEEPER_IDS = List.of(
            "it-career-p1", "it-career-p2", "it-career-p3", "it-career-p4");

    @BeforeEach
    void setUp() {
        cleanup();

        managerA = insertManager("it-career-a", "IT Career A");
        managerB = insertManager("it-career-b", "IT Career B");
        managerC = insertManager("it-career-c", "IT Career C");
        managerD = insertManager("it-career-d", "IT Career D");
        managerE = insertManager("it-career-e", "IT Career E");
        managerF = insertManager("it-career-f", "IT Career F");

        insertPlayer("it-career-p1");
        insertPlayer("it-career-p2");
        insertPlayer("it-career-p3");
        insertPlayer("it-career-p4");

        // --- leagueConservation: 4 rosters, one fully-paired scored week ---
        leagueConservation = insertLeague("it-career-conservation", 2025, 4, "complete");
        insertRosterSeason(leagueConservation, managerA, 1, 1, 0, 100.00);
        insertRosterSeason(leagueConservation, managerB, 2, 0, 1, 80.00);
        insertRosterSeason(leagueConservation, managerC, 3, 1, 0, 95.00);
        insertRosterSeason(leagueConservation, managerD, 4, 0, 1, 60.00);
        insertWeekPoints(leagueConservation, 2025, 1, 1, 100.00, "{\"it-career-p1\":100.0}");
        insertWeekPoints(leagueConservation, 2025, 1, 2, 80.00, "{\"it-career-p2\":80.0}");
        insertWeekPoints(leagueConservation, 2025, 1, 3, 95.00, "{\"it-career-p3\":95.0}");
        insertWeekPoints(leagueConservation, 2025, 1, 4, 60.00, "{\"it-career-p4\":60.0}");
        insertMatchup(leagueConservation, 2025, 1, 1, 100);
        insertMatchup(leagueConservation, 2025, 1, 2, 100);
        insertMatchup(leagueConservation, 2025, 1, 3, 101);
        insertMatchup(leagueConservation, 2025, 1, 4, 101);

        // --- leagueUnplayed: ingested, zero scored weeks (mirrors the real
        // NBA-2026 case baseline.md records). managerA's row here is
        // deliberately "poisoned" with nonzero wins/losses/points that must
        // NOT reach managerA's career totals if `counted` is honoured.
        leagueUnplayed = insertLeague("it-career-unplayed", 2026, 4, "in_season");
        insertRosterSeason(leagueUnplayed, managerA, 1, 5, 5, 999.99);
        insertRosterSeason(leagueUnplayed, managerE, 2, 0, 0, 0.00);

        // --- leagueZeroPotential: one scored week, but players_points is
        // empty -- RealizedLineupService cannot seat anyone, so potential is
        // 0 for the only week this season has.
        leagueZeroPotential = insertLeague("it-career-zero-potential", 2025, 1, "complete");
        insertRosterSeason(leagueZeroPotential, managerF, 1, 1, 0, 50.00);
        insertWeekPoints(leagueZeroPotential, 2025, 1, 1, 50.00, "{}");

        // --- leaguePlayoffBound: playoff_week_start = 2, so only week 1 is
        // "regular season". Week 1 mirrors leagueConservation's own scores
        // (100/80/95/60), whose winsAboveExpected for roster 1 is a known,
        // clean 0.0 (roster 1 is the top scorer, beats everyone, and the
        // all-play share for a 4-team week a top scorer sweeps is exactly
        // 1.0 -- see the conservation test above). Week 2's scores are
        // deliberately extreme so that if T030's bound leaked, this would
        // fail loudly rather than by a rounding coincidence.
        managerG = insertManager("it-career-g", "IT Career G");
        managerH = insertManager("it-career-h", "IT Career H");
        managerI = insertManager("it-career-i", "IT Career I");
        managerJ = insertManager("it-career-j", "IT Career J");
        leaguePlayoffBound = insertLeagueWithPlayoffStart("it-career-playoff-bound", 2025, 4, "complete", 2);
        insertRosterSeason(leaguePlayoffBound, managerG, 1, 2, 0, 105.00);
        insertRosterSeason(leaguePlayoffBound, managerH, 2, 0, 2, 380.00);
        insertRosterSeason(leaguePlayoffBound, managerI, 3, 1, 1, 345.00);
        insertRosterSeason(leaguePlayoffBound, managerJ, 4, 1, 1, 70.00);
        insertWeekPoints(leaguePlayoffBound, 2025, 1, 1, 100.00, "{}");
        insertWeekPoints(leaguePlayoffBound, 2025, 1, 2, 80.00, "{}");
        insertWeekPoints(leaguePlayoffBound, 2025, 1, 3, 95.00, "{}");
        insertWeekPoints(leaguePlayoffBound, 2025, 1, 4, 60.00, "{}");
        insertMatchup(leaguePlayoffBound, 2025, 1, 1, 500);
        insertMatchup(leaguePlayoffBound, 2025, 1, 2, 500);
        insertMatchup(leaguePlayoffBound, 2025, 1, 3, 501);
        insertMatchup(leaguePlayoffBound, 2025, 1, 4, 501);
        // Week 2, the playoff week: roster 1 collapses and roster 2 explodes.
        // If this reached winsAboveExpected, roster 1's figure would move far
        // from 0.0 -- it must not move at all.
        insertWeekPoints(leaguePlayoffBound, 2025, 2, 1, 5.00, "{}");
        insertWeekPoints(leaguePlayoffBound, 2025, 2, 2, 300.00, "{}");
        insertWeekPoints(leaguePlayoffBound, 2025, 2, 3, 250.00, "{}");
        insertWeekPoints(leaguePlayoffBound, 2025, 2, 4, 10.00, "{}");
        insertMatchup(leaguePlayoffBound, 2025, 2, 1, 510);
        insertMatchup(leaguePlayoffBound, 2025, 2, 2, 510);
        insertMatchup(leaguePlayoffBound, 2025, 2, 3, 511);
        insertMatchup(leaguePlayoffBound, 2025, 2, 4, 511);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("delete from league where sleeper_id in (?, ?, ?, ?)", LEAGUE_SLEEPER_IDS.toArray());
        jdbc.update("delete from manager where sleeper_user_id in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                SLEEPER_USER_IDS.toArray());
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id in (?, ?, ?, ?)",
                PLAYER_SLEEPER_IDS.toArray());
    }

    private long insertManager(String sleeperUserId, String displayName) {
        return jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, displayName);
    }

    private void insertPlayer(String sleeperId) {
        jdbc.update("insert into player (sport, sleeper_id, name, positions) values ('nfl', ?, ?, '{QB}')",
                sleeperId, sleeperId);
    }

    private long insertLeague(String sleeperId, int season, int totalRosters, String status) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions)
                values ('nfl', ?, ?, ?, ?, ?, '{QB}')
                returning id
                """, Long.class, season, sleeperId, "IT League " + sleeperId, totalRosters, status);
    }

    /** T030: a league whose {@code settings_json} carries a real playoff_week_start, so
     * {@link ExpectedWinsService#regularSeasonBound} has something to bound against. */
    private long insertLeagueWithPlayoffStart(String sleeperId, int season, int totalRosters, String status,
                                              int playoffWeekStart) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions, settings_json)
                values ('nfl', ?, ?, ?, ?, ?, '{QB}', jsonb_build_object('playoff_week_start', ?))
                returning id
                """, Long.class, season, sleeperId, "IT League " + sleeperId, totalRosters, status, playoffWeekStart);
    }

    private void insertRosterSeason(long leagueId, long managerId, int rosterId, int wins, int losses, double pointsFor) {
        jdbc.update("""
                insert into roster_season (league_id, manager_id, roster_id, wins, losses, points_for)
                values (?, ?, ?, ?, ?, ?)
                """, leagueId, managerId, rosterId, wins, losses, pointsFor);
    }

    private void insertWeekPoints(long leagueId, int season, int week, int rosterId, double points, String playersPointsJson) {
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """, leagueId, season, week, rosterId, points, playersPointsJson);
    }

    private void insertMatchup(long leagueId, int season, int week, int rosterId, int matchupId) {
        jdbc.update("""
                insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                values (?, ?, ?, ?, ?)
                """, leagueId, season, week, rosterId, matchupId);
    }

    private ManagerCareerService.CareerProfile nfl(long managerId) {
        return careers.forManager(managerId).stream()
                .filter(c -> c.sport() == Sport.NFL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no nfl CareerProfile for manager " + managerId));
    }

    // ------------------------------------------------------------- T030(a)

    /**
     * US3.4: an uncounted season (zero scored weeks) is listed but
     * contributes to nothing. managerA's leagueUnplayed row is seeded with
     * poisoned wins=5/losses=5/pointsFor=999.99 specifically so that a bug
     * which sums it anyway is caught rather than passing by coincidence.
     */
    @Test
    void anUncountedSeasonIsListedButContributesToNoAverage() {
        ManagerCareerService.CareerProfile career = nfl(managerA);

        assertEquals(1, career.seasonsCounted(), "only leagueConservation has scored weeks");
        assertEquals(2, career.seasons().size(), "both roster-seasons are still listed");

        boolean anyUncounted = career.seasons().stream().anyMatch(se -> !se.counted());
        assertTrue(anyUncounted, "leagueUnplayed's row must be present and marked uncounted");

        assertEquals(1, career.wins(), "the poisoned wins=5 from the uncounted season must not be summed in");
        assertEquals(0, career.losses(), "the poisoned losses=5 from the uncounted season must not be summed in");
        assertEquals(100.00, career.pointsFor(), 1e-9,
                "the poisoned pointsFor=999.99 from the uncounted season must not be summed in");
    }

    // ------------------------------------------------------------- T030(b)

    /** US3.4 / data-model.md: null, never 0, when no games have been played. */
    @Test
    void winRateIsNullNotZeroWhenNoGamesHaveBeenPlayed() {
        ManagerCareerService.CareerProfile career = nfl(managerE);

        assertEquals(0, career.seasonsCounted());
        assertNull(career.winRate(), "no games played -- winRate must be null, not 0");
    }

    // ------------------------------------------------------------- T030(c)

    /**
     * data-model.md, quoted verbatim in the task: "Never 1.0: a team that has
     * not scored has no efficiency, and printing a perfect score for it would
     * be the most flattering possible wrong answer." managerF's only season
     * IS counted (a week was scored) but its players_points is empty, so
     * potential is 0 for that week -- averageEfficiency must still be null,
     * not a default of 1.0 nor of 0.0.
     */
    @Test
    void averageEfficiencyIsNullNotOneWhenPotentialIsZero() {
        ManagerCareerService.CareerProfile career = nfl(managerF);

        assertEquals(1, career.seasonsCounted(), "the week WAS scored, so the season counts");
        assertEquals(0, career.weeksCounted(), "no week had a usable per-player breakdown");
        assertTrue(career.weeksExcluded() >= 1, "the one week must show up as excluded, not silently dropped");
        assertNull(career.averageEfficiency(), "no potential to divide by -- must be null, never 1.0");
    }

    // ------------------------------------------------------------- T031

    /**
     * SC-004/US3.5, quoted from ExpectedWinsService's own invariant: summed
     * across every roster in one scored, fully-paired league-season, the
     * expected-wins model hands out exactly as many wins as were actually
     * played, so winsAboveExpected sums to zero. This is the proof that
     * {@link ManagerCareerService}'s per-manager loop reads each manager's
     * own TeamRow once and does not double-count or drop a season -- if it
     * did, this sum would drift away from zero.
     */
    @Test
    void winsAboveExpectedConservesAcrossEveryManagerInOneLeagueSeason() {
        double sum = 0;
        for (long managerId : List.of(managerA, managerB, managerC, managerD)) {
            ManagerCareerService.CareerProfile career = nfl(managerId);
            Double wae = career.winsAboveExpected();
            assertNotNull(wae, "manager " + managerId + " played a fully-paired week and must have a figure");
            sum += wae;
        }
        assertEquals(0.0, sum, 1e-6, "winsAboveExpected must conserve to zero across the whole league-season");
    }

    // ------------------------------------------------------------- T030 (008-season-superlatives)

    /**
     * specs/008-season-superlatives T030: {@link ManagerCareerService} now asks
     * {@link ExpectedWinsService#forLeagueRegularSeason} rather than the old
     * unbounded {@code forLeague}, so a season's playoff-week games must not
     * reach the career winsAboveExpected sum. leaguePlayoffBound's week 1
     * (the regular season) is roster 1 sweeping a 4-team week it top-scored,
     * whose winsAboveExpected is a clean 0.0; week 2 (the playoff week, per
     * {@code playoff_week_start = 2}) sends roster 1's score to the bottom
     * and roster 2's to the top -- if that leaked in, roster 1's figure would
     * move far away from 0.0, not stay put.
     */
    @Test
    void playoffWeekGamesDoNotReachTheCareerWinsAboveExpectedSum() {
        ManagerCareerService.CareerProfile career = nfl(managerG);

        assertNotNull(career.winsAboveExpected(), "roster 1 played a fully-paired regular-season week");
        assertEquals(0.0, career.winsAboveExpected(), 1e-6,
                "week 2's extreme, playoff-week scores must not move managerG's winsAboveExpected off its "
                        + "week-1-only value of 0.0");
    }

    // ------------------------------------------------------------- T068

    /**
     * specs/006-deeper-history-both-sports T068 (US6): {@code tradesPerSeason}
     * stays an omission with its own stated reason even now that {@code
     * waivers} (T073) sits right beside it on the same CareerProfile -- it
     * must never be silently upgraded to a real (flattering-zero) number just
     * because a NEIGHBOURING figure on the same object started answering.
     * research R8 measured why it cannot: 25 trades exist across this
     * database and every one has a null {@code manager_id}, because
     * {@code league_transaction} carries a single {@code roster_id} and a
     * Sleeper trade names several.
     */
    @Test
    void tradesPerSeasonStaysOmittedWithItsReasonEvenAlongsideWaivers() {
        ManagerCareerService.CareerProfile career = nfl(managerA);

        assertNotNull(career.waivers(), "waivers must be present -- the regression this test guards against "
                + "is waivers replacing, rather than sitting beside, the tradesPerSeason omission");

        ManagerCareerService.Unavailable trades = career.unavailable().stream()
                .filter(u -> u.figure().equals("tradesPerSeason"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tradesPerSeason must still be named as unavailable"));
        assertEquals("trades are not attributed to a manager (a trade names several rosters)", trades.reason());
    }

    // ------------------------------------------------------------- sanity

    /** Confirms the fixture itself measures what the tests above assume it does. */
    @Test
    void theConservationFixtureActuallyScoresAWinner() {
        ManagerCareerService.CareerProfile career = nfl(managerA);
        assertEquals(1, career.wins());
        assertEquals(1.0, career.averageEfficiency(), 1e-9,
                "a single-slot roster whose one starter is its only scored player is trivially optimal");
    }
}
