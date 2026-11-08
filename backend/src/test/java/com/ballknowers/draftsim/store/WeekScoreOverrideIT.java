package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.ScoreRow;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 057 T010, the regression test the brief requires: with a {@code week_score_override} row present, the
 * store reads that hand out team-week totals ({@code pairedWithScores}, {@code allScores}, {@code extremes},
 * {@code through}) return the entered score, not the feed's, against real Postgres. The fixture reproduces the
 * bug's shape: the feed has roster 2 beating roster 1 in week 1 (233.0 to 227.0), and the entered scores
 * reverse it. SKIPS when Postgres is unreachable, so read the skip count.
 */
@SpringBootTest
class WeekScoreOverrideIT {

    static final String LEAGUE = "it057-resolver";
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMatchupRepository matchups;
    @Autowired private RosterWeekPointsRepository weekPoints;
    @Autowired private WeekScoreResolver resolver;
    @Autowired private WeekScoreOverrideRepository overrideRepo;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions)
                values ('nba', 2095, ?, 'IT 057', 2, 'complete', '{PG}') returning id
                """, Long.class, LEAGUE);
        jdbc.update("insert into roster_season (league_id, roster_id) values (?, 1), (?, 2)", leagueId, leagueId);
        // week 1: the feed has roster 2 ahead of roster 1, by 6.0. week 2: roster 2 again, 110 to 100.
        feed(1, 1, 227.0);
        feed(1, 2, 233.0);
        feed(2, 1, 100.0);
        feed(2, 2, 110.0);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
    }

    private void feed(int week, int roster, double points) {
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, 2095, ?, ?, ?, '{}'::jsonb)""", leagueId, week, roster, points);
        jdbc.update("""
                insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                values (?, 2095, ?, ?, 1)""", leagueId, week, roster);
    }

    private void override(int week, int roster, String points) {
        jdbc.update("insert into week_score_override (league_id, week, roster_id, points) values (?, ?, ?, ?)",
                leagueId, week, roster, new BigDecimal(points));
    }

    private PairedGame game(int week) {
        return matchups.pairedWithScores(List.of(leagueId), WeekBound.ALL_WEEKS).stream()
                .filter(g -> g.week() == week).findFirst().orElseThrow();
    }

    @Test
    void replaceWeekSurvivesARowThatAppearsAfterItsDelete_andReplacesTheWholeWeek() {
        override(1, 1, "10.00");
        overrideRepo.replaceWeek(leagueId, 1, Map.of(1, new BigDecimal("239.00"), 2, new BigDecimal("231.00")), null);
        overrideRepo.replaceWeek(leagueId, 1, Map.of(1, new BigDecimal("240.00"), 2, new BigDecimal("232.00")), null);
        assertEquals(2, overrideRepo.forWeek(leagueId, 1).size());
        assertEquals(0, new BigDecimal("240.00").compareTo(overrideRepo.forWeek(leagueId, 1).get(0).points()));
    }

    @Test
    void withNoOverrideTheReadsAreTheFeed() {
        assertEquals(0, new BigDecimal("227.00").compareTo(game(1).aPoints()));
        assertEquals(0, new BigDecimal("233.00").compareTo(game(1).bPoints()));
        assertTrue(resolver.resolve(List.of(leagueId)).isEmpty());
    }

    @Test
    void anOverrideRowMakesPairedWithScoresReturnTheEnteredScoresNotTheFeedOnes() {
        override(1, 1, "239.00");
        override(1, 2, "231.00");
        PairedGame g = game(1);
        assertEquals(0, new BigDecimal("239.00").compareTo(g.aPoints()), "roster 1 now wins week 1");
        assertEquals(0, new BigDecimal("231.00").compareTo(g.bPoints()));
        // the week that was not overridden is still the feed
        assertEquals(0, new BigDecimal("100.00").compareTo(game(2).aPoints()));
        assertEquals(0, new BigDecimal("110.00").compareTo(game(2).bPoints()));
        assertEquals(Map.of(1, WeekScoreResolver.Source.COMMISSIONER, 2, WeekScoreResolver.Source.FEED),
                resolver.sources(leagueId));
    }

    @Test
    void anOverrideRowMakesAllScoresThroughAndExtremesReturnTheEnteredScores() {
        override(1, 1, "239.00");
        override(1, 2, "231.00");

        List<ScoreRow> all = weekPoints.allScores(List.of(leagueId), WeekBound.ALL_WEEKS);
        assertEquals(4, all.size());
        assertEquals(0, new BigDecimal("239.00").compareTo(point(all, 1, 1)));
        assertEquals(0, new BigDecimal("231.00").compareTo(point(all, 1, 2)));
        assertEquals(0, new BigDecimal("110.00").compareTo(point(all, 2, 2)), "week 2 stays on the feed");

        // the highest week was roster 2's 233.0 on the feed; with the entered scores it is roster 1's 239.0
        ScoreRow top = weekPoints.extremes(List.of(leagueId), true, 1, WeekBound.ALL_WEEKS).get(0);
        assertEquals(1, top.rosterId());
        assertEquals(0, new BigDecimal("239.00").compareTo(top.points()));
        // and second place is the entered 231.0, not the feed's 227.0 / 233.0
        List<ScoreRow> topTwo = weekPoints.extremes(List.of(leagueId), true, 2, WeekBound.ALL_WEEKS);
        assertEquals(0, new BigDecimal("231.00").compareTo(topTwo.get(1).points()));
        // lowest: 100.0 (roster 1, week 2); the feed's 227.0 is gone from the table entirely
        ScoreRow low = weekPoints.extremes(List.of(leagueId), false, 1, WeekBound.ALL_WEEKS).get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(low.points()));

        List<WeekPoint> through = weekPoints.through(leagueId, 1);
        assertEquals(2, through.size());
        assertEquals(239.0, through.stream().filter(p -> p.rosterId() == 1).findFirst().orElseThrow().startersPoints());
        assertEquals(231.0, through.stream().filter(p -> p.rosterId() == 2).findFirst().orElseThrow().startersPoints());
    }

    @Test
    void extremesOrdersTiesBySeasonDescThenWeekAscThenRosterAsc() {
        // two equal scores: the earlier week wins the tie, then the lower roster (the SQL ordering extremes replaced)
        override(1, 1, "150.00");
        override(1, 2, "150.00");
        List<ScoreRow> top = weekPoints.extremes(List.of(leagueId), true, 2, WeekBound.ALL_WEEKS);
        assertEquals(List.of(1, 2), top.stream().map(ScoreRow::rosterId).toList());
        assertEquals(List.of(1, 1), top.stream().map(ScoreRow::week).toList());
    }

    @Test
    void anOverrideBeatsAPlausibleOfficialSnapshotToo() {
        // official fpts through week 1 would make roster 2 win 233 to 227, the same as the feed (plausible).
        jdbc.update("insert into roster_fpts_snapshot (league_id, roster_id, through_week, fpts, fpts_against) values (?, 1, 1, 225, 0), (?, 2, 1, 235, 0)",
                leagueId, leagueId);
        assertEquals(WeekScoreResolver.Source.OFFICIAL, resolver.sources(leagueId).get(1));
        assertEquals(0, new BigDecimal("225").compareTo(game(1).aPoints()));
        override(1, 1, "239.00");
        override(1, 2, "231.00");
        assertEquals(WeekScoreResolver.Source.COMMISSIONER, resolver.sources(leagueId).get(1));
        assertEquals(0, new BigDecimal("239.00").compareTo(game(1).aPoints()));
    }

    @Test
    void aReIngestOfTheFeedDoesNotTouchTheOverride() {
        override(1, 1, "239.00");
        override(1, 2, "231.00");
        // what an ingest does: upsert the week's feed rows with new numbers
        for (int roster = 1; roster <= 2; roster++) {
            weekPoints.upsert(new RosterWeekPointsRepository.Row(leagueId, 2095, 1, roster, 400.0 + roster, "{}"));
        }
        assertEquals(0, new BigDecimal("239.00").compareTo(game(1).aPoints()));
    }

    private static BigDecimal point(List<ScoreRow> rows, int week, int roster) {
        return rows.stream().filter(r -> r.week() == week && r.rosterId() == roster).findFirst().orElseThrow().points();
    }
}
