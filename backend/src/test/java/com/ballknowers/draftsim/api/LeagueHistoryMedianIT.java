package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.ExpectedWinsService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 054: the per-season {@code median} block on {@code GET /api/leagues/{id}/history}, against real
 * Postgres. Two seasons of one chain with the same four-roster, three-week scores: the older one plays no
 * median game (a what-if), the newer one has Sleeper's median setting on (already included in its W-L).
 *
 * <p>The scores are the hand-worked fixture in {@code MedianWhatIfServiceTest}. SKIPS when Postgres is
 * unreachable, so the caller must read the skip count.
 */
@SpringBootTest(properties = "draftsim.owner.sleeper-user-id=it054-owner")
class LeagueHistoryMedianIT {

    private static final String OLD = "it054-old";
    private static final String NEW = "it054-new";
    private static final String USER = "it054-owner";

    // week -> score per roster 1..4
    private static final double[][] SCORES = {
            {200, 100, 90, 80},
            {50, 110, 60, 40},
            {70, 95, 100, 80}};
    // week -> matchup id per roster 1..4 (1v2 3v4 / 1v3 2v4 / 1v4 2v3)
    private static final int[][] PAIRS = {{1, 1, 2, 2}, {1, 2, 1, 2}, {1, 2, 2, 1}};

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping history median IT");
    }

    @Autowired private LeagueHistoryController controller;
    @Autowired private ExpectedWinsService expectedWins;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        tearDown();
        // The scoping gate sees a league through a manager whose roster is in its chain.
        long me = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, 'It054') returning id", Long.class, USER);
        seed(OLD, 2025, null, 0, me);
        seed(NEW, 2026, OLD, 1, me);
        bracket(OLD, 3, 1);
    }

    /** A one-match winners bracket between two rosters (the playoff field of a 2-team playoff). */
    private void bracket(String sleeperId, int t1, int t2) {
        jdbc.update("delete from league_bracket_match where league_id = (select id from league where sleeper_id = ?)", sleeperId);
        jdbc.update("""
                insert into league_bracket_match (league_id, match_id, round, t1_roster_id, t2_roster_id,
                                                  winner_roster_id, loser_roster_id, placement)
                select id, 1, 1, ?, ?, ?, ?, 1 from league where sleeper_id = ?
                """, t1, t2, t1, t2, sleeperId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id in (?, ?)", OLD, NEW);
        jdbc.update("delete from manager where sleeper_user_id = ?", USER);
    }

    private void seed(String sleeperId, int season, String previous, int medianMatch, long managerId) {
        long leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions,
                                    previous_league_id, settings_json)
                values ('nfl', ?, ?, 'IT median', 4, 'complete', '{QB}', ?, ?::jsonb)
                returning id
                """, Long.class, season, sleeperId, previous,
                "{\"playoff_week_start\":4,\"playoff_teams\":2,\"playoff_seed_type\":0,\"league_average_match\":" + medianMatch + "}");
        // Official regular-season records and points for. final_placement is champion-only (r3), exactly as
        // both real ingests write it.
        int[][] record = {{1, 2}, {1, 2}, {3, 0}, {1, 2}};
        double[] pf = {320, 305, 250, 200};
        Integer[] placement = {null, null, 1, null};
        for (int r = 1; r <= 4; r++) {
            jdbc.update("""
                    insert into roster_season (league_id, roster_id, manager_id, wins, losses, ties, points_for, final_placement)
                    values (?, ?, ?, ?, ?, 0, ?, ?)
                    """, leagueId, r, r == 1 ? managerId : null, record[r - 1][0], record[r - 1][1], pf[r - 1], placement[r - 1]);
        }
        for (int w = 1; w <= 3; w++) {
            for (int r = 1; r <= 4; r++) {
                jdbc.update("""
                        insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                        values (?, ?, ?, ?, ?, '{}'::jsonb)
                        """, leagueId, season, w, r, SCORES[w - 1][r - 1]);
                jdbc.update("""
                        insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                        values (?, ?, ?, ?, ?)
                        """, leagueId, season, w, r, PAIRS[w - 1][r - 1]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> season(int year) {
        ResponseEntity<?> r = controller.history(NEW, USER);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "status " + r.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        return ((List<Map<String, Object>>) body.get("seasons")).stream()
                .filter(s -> ((Integer) s.get("season")) == year).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> median(Map<String, Object> season) {
        assertTrue(season.containsKey("median"), "the median key is always present");
        return (Map<String, Object>) season.get("median");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> teams(Map<String, Object> median) {
        return (List<Map<String, Object>>) median.get("teams");
    }

    @SuppressWarnings("unchecked")
    private static int[] wlt(Object o) {
        Map<String, Object> m = (Map<String, Object>) o;
        return new int[] {(Integer) m.get("wins"), (Integer) m.get("losses"), (Integer) m.get("ties")};
    }

    @Test
    void theOlderMedianOffSeasonCarriesItsOwnWhatIfWithTheInvariantsHolding() {
        Map<String, Object> m = median(season(2025));
        assertEquals("WHAT_IF", m.get("status"));
        assertEquals("OFFICIAL", m.get("realSource"));
        assertEquals(3, m.get("weeksCounted"));
        assertEquals(0, m.get("driftGames"));
        assertEquals(4, teams(m).size());

        Map<Integer, Map<String, Object>> byRoster = new HashMap<>();
        for (Map<String, Object> t : teams(m)) {
            byRoster.put((Integer) t.get("rosterId"), t);
            int[] med = wlt(t.get("median"));
            int[] real = wlt(t.get("real"));
            int[] with = wlt(t.get("withMedian"));
            assertEquals((Integer) t.get("gamesCounted"), med[0] + med[1] + med[2], "invariant 1");
            for (int i = 0; i < 3; i++) assertEquals(real[i] + med[i], with[i], "invariant 2");
            assertEquals(med[0], t.get("winsGained"));
        }
        // The hand-worked fixture: r1 and r2 swap places across the line of 2.
        assertEquals(2, byRoster.get(1).get("actualPlace"));
        assertEquals(3, byRoster.get(1).get("whatIfPlace"));
        assertEquals("MISSED", byRoster.get(1).get("playoffCross"));
        assertEquals("MADE", byRoster.get(2).get("playoffCross"));
        assertNull(m.get("playoffLineWithheld"));
        assertEquals(2, m.get("playoffTeams"));
    }

    @Test
    void aStoredBracketThatDisagreesWithTheOrderWithholdsAsOrderDisagrees() {
        bracket(OLD, 3, 2);   // this app's top two are r3 and r1
        Map<String, Object> m = median(season(2025));
        assertEquals("ORDER_DISAGREES", m.get("playoffLineWithheld"));
        assertNull(m.get("playoffTeams"));
        for (Map<String, Object> t : teams(m)) assertNull(t.get("playoffCross"));
    }

    @Test
    void aFinishedSeasonWithNoStoredBracketIsBracketUnknown() {
        jdbc.update("delete from league_bracket_match where league_id = (select id from league where sleeper_id = ?)", OLD);
        Map<String, Object> m = median(season(2025));
        assertEquals("WHAT_IF", m.get("status"));
        assertEquals("BRACKET_UNKNOWN", m.get("playoffLineWithheld"));
        assertNull(m.get("playoffTeams"));
        for (Map<String, Object> t : teams(m)) assertNull(t.get("playoffCross"));
    }

    @Test
    void aSeasonInProgressShowsTheLineWithoutAnyBracket() {
        jdbc.update("delete from league_bracket_match where league_id = (select id from league where sleeper_id = ?)", OLD);
        jdbc.update("update league set status = 'in_season' where sleeper_id = ?", OLD);
        Map<String, Object> m = median(season(2025));
        assertNull(m.get("playoffLineWithheld"));
        assertEquals(2, m.get("playoffTeams"));
    }

    @Test
    void theNewerSeasonWithTheMedianSettingOnIsLabelledAlreadyOnWithNoWhatIfFields() {
        Map<String, Object> m = median(season(2026));
        assertEquals("ALREADY_ON", m.get("status"));
        assertNull(m.get("realSource"));
        for (Map<String, Object> t : teams(m)) {
            assertNotNull(t.get("median"));
            assertNotNull(t.get("allPlay"));
            for (String k : List.of("real", "withMedian", "winsGained", "actualPlace", "whatIfPlace", "playoffCross")) {
                assertTrue(t.containsKey(k), k + " is present on the wire");
                assertNull(t.get(k), k);
            }
        }
    }

    @Test
    void medianAndAllPlayEqualTheLuckPagesFiguresForTheSameSeasonExactly() {
        ExpectedWinsService.Result luck = expectedWins.forLeagueRegularSeason(NEW).orElseThrow();
        assertTrue(luck.available());
        Map<String, Object> m = median(season(2026));
        assertEquals(luck.teams().size(), teams(m).size());
        for (Map<String, Object> t : teams(m)) {
            ExpectedWinsService.TeamRow row = luck.teams().stream()
                    .filter(x -> x.rosterId() == (Integer) t.get("rosterId")).findFirst().orElseThrow();
            assertArrayEquals(new int[] {row.median().wins(), row.median().losses(), row.median().ties()}, wlt(t.get("median")));
            assertArrayEquals(new int[] {row.allPlay().wins(), row.allPlay().losses(), row.allPlay().ties()}, wlt(t.get("allPlay")));
        }
    }
}
