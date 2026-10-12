package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.ExpectedWinsService.ScheduleSwap;
import com.ballknowers.draftsim.engine.ExpectedWinsService.WinLossTie;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 031 SC-001: through the real read path, the diagonal of the schedule-swap matrix is each
 * team's real regular-season W-L-T counted separately, straight from the stored games with its own
 * SQL and one {@code Double.compare} per game. One test per league, each with its own named
 * Assumption, so a missing league is a visible skip. READ THE SKIP COUNT, not just BUILD SUCCESSFUL.
 *
 * <p>Not asserted here: Sleeper's own standings. NBA 2025 differs from them for 4 rosters (plan-review F4).
 */
@SpringBootTest
class ScheduleSwapIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @Autowired private ExpectedWinsService service;
    @Autowired private ScoredWeeks scoredWeeks;
    @Autowired private JdbcTemplate jdbc;

    private static boolean reachable() {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Test void footballBallKnowers2025() { check(5, "1254190892974084096", "(Foot) Ball Knowers NFL 2025"); }
    @Test void westCoast2025()           { check(9466, "1262506916429430784", "NFL 2025 league 9466"); }
    @Test void nba2025()                 { check(211, "1229352720222134272", "NBA 2025"); }
    @Test void nba2024()                 { check(212, "1141438340626231296", "NBA 2024"); }

    private void check(long leagueId, String sleeperId, String label) {
        Assumptions.assumeTrue(reachable(), "no local Postgres at " + JDBC_URL + " -- skipping " + label);
        Assumptions.assumeTrue(jdbc.queryForObject("select count(*) from league where id = ? and sleeper_id = ?",
                Integer.class, leagueId, sleeperId) == 1, label + " (league " + leagueId + ") is not in the local database");
        Optional<ExpectedWinsService.Result> found = service.forLeagueRegularSeason(sleeperId);
        assertTrue(found.isPresent());
        ExpectedWinsService.Result result = found.get();
        assertTrue(result.available(), label + ": " + result.reason());
        ScheduleSwap s = result.scheduleSwap();
        assertNotNull(s, label);
        int n = s.rosterIds().size();
        assertEquals(result.teams().stream().map(ExpectedWinsService.TeamRow::rosterId).toList(), s.rosterIds(),
                "rows and columns follow the teams order");
        assertEquals(n, s.cells().size());

        Map<Integer, int[]> real = realRecords(leagueId);
        Map<Integer, Double> actual = new HashMap<>();
        result.teams().forEach(t -> actual.put(t.rosterId(), t.actualWins()));
        int weeks = result.weeksScored();
        StringBuilder diag = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int rid = s.rosterIds().get(i);
            WinLossTie d = s.cells().get(i).get(i);
            int[] r = real.get(rid);
            assertNotNull(r, label + " roster " + rid + " has no real record");
            assertEquals(new WinLossTie(r[0], r[1], r[2]), d, label + " roster " + rid + " diagonal vs stored games");
            assertEquals(actual.get(rid), d.wins() + d.ties() / 2.0, 1e-9, label + " roster " + rid + " vs actualWins");
            diag.append(rid).append(':').append(d.wins()).append('-').append(d.losses()).append('-').append(d.ties()).append(' ');
            for (int j = 0; j < n; j++) {
                WinLossTie c = s.cells().get(i).get(j);
                assertEquals(weeks, c.wins() + c.losses() + c.ties(), label + " cell " + i + "," + j + " (no byes)");
            }
        }
        // no byes: the other N-1 schedules average to expected wins, difficulty averages 0.5
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                WinLossTie c = s.cells().get(i).get(j);
                sum += c.wins() + c.ties() / 2.0;
            }
            double expected = result.teams().get(i).actualWins() - result.teams().get(i).winsAboveExpected();
            assertEquals(expected, sum / (n - 1), 0.011, label + " roster " + s.rosterIds().get(i));
        }
        double meanDifficulty = s.difficultyWinRate().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertEquals(0.5, meanDifficulty, 0.002, label);
        System.out.println("REPORT " + label + " diagonal == stored-game W-L-T for all " + n + " rosters: " + diag);
    }

    /** Own SQL, not the service's: regular-season weeks (below playoff_week_start), final weeks, both sides scored. */
    private Map<Integer, int[]> realRecords(long leagueId) {
        Integer playoffStart = jdbc.queryForObject(
                "select coalesce((settings_json->>'playoff_week_start')::int, 0) from league where id = ?",
                Integer.class, leagueId);
        int bound = playoffStart != null && playoffStart >= 2 ? playoffStart - 1 : Integer.MAX_VALUE;
        ScoredWeeks.Snapshot scored = scoredWeeks.of(leagueId);
        Integer season = jdbc.queryForObject("select season from league where id = ?", Integer.class, leagueId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.week, a.roster_id as ar, wa.starters_points as ap, b.roster_id as br, wb.starters_points as bp
                from league_matchup a
                join league_matchup b on b.league_id = a.league_id and b.season = a.season and b.week = a.week
                 and b.matchup_id = a.matchup_id and b.roster_id > a.roster_id
                join roster_week_points wa on wa.league_id = a.league_id and wa.week = a.week and wa.roster_id = a.roster_id
                join roster_week_points wb on wb.league_id = b.league_id and wb.week = b.week and wb.roster_id = b.roster_id
                where a.league_id = ? and a.season = ? and a.week <= ?
                  and wa.starters_points is not null and wb.starters_points is not null
                """, leagueId, season, bound);
        Map<Integer, int[]> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int week = ((Number) row.get("week")).intValue();
            if (!scored.isFinal(week)) continue;
            int ar = ((Number) row.get("ar")).intValue();
            int br = ((Number) row.get("br")).intValue();
            int cmp = Double.compare(((Number) row.get("ap")).doubleValue(), ((Number) row.get("bp")).doubleValue());
            int[] a = out.computeIfAbsent(ar, k -> new int[3]);
            int[] b = out.computeIfAbsent(br, k -> new int[3]);
            if (cmp > 0) { a[0]++; b[1]++; }
            else if (cmp < 0) { a[1]++; b[0]++; }
            else { a[2]++; b[2]++; }
        }
        return out;
    }
}
