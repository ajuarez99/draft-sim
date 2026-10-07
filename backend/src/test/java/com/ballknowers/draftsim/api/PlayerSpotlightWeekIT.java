package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.SportLeagueSeasonSource;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 014 T023, amended after design review: for a football league the spotlight's
 * {@code period.week} is the newest week whose sport-wide {@code sport_week_stats.final} is true,
 * NOT the weekly report's week. Seeded with stats weeks 1-2 final and 3 not, and the league's own
 * weeks final only through 1: the spotlight says 2 (weekFinal true) while {@code GET
 * /weekly-report/0} says 1 -- the real-world case (Sleeper finalizes a league's week later than
 * its stats settle). Also: no {@code topOfNight} key, and a season with nothing stored says
 * NO_WEEK_SCORED.
 *
 * <p>Throwaway NFL season 2099, real Postgres, real controllers. SKIPS when the local Postgres is
 * unreachable; read the skip count.
 */
@SpringBootTest
class PlayerSpotlightWeekIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE = "it-014-nfl-league";
    private static final String EMPTY_LEAGUE = "it-014-nfl-empty-league";
    private static final String USER_X = "it-014-week-user-x";
    private static final int SEASON = 2099;
    /** A season with no sport_week_stats rows at all. */
    private static final int EMPTY_SEASON = 2098;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping PlayerSpotlightWeek IT");
    }

    /** The sport's stored current season (a real table once trending lands) must not decide a 2099 league. */
    @MockitoBean private SportLeagueSeasonSource currentSeason;

    @Autowired private PlayerSpotlightController spotlight;
    @Autowired private WeeklyReportController weekly;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private JdbcTemplate jdbc;

    private static boolean seeded;

    private void seedOnce() {
        if (seeded) return;
        cleanUp(jdbc);

        long x = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, USER_X, "IT Week X");
        long leagueId = nflLeague(LEAGUE, x, SEASON);
        nflLeague(EMPTY_LEAGUE, x, EMPTY_SEASON);

        // Weeks 1-3 stored for LEAGUE. The LEAGUE's own final marker is only week 1 (so the weekly
        // report defaults to 1); the sport-wide stats are final for 1 and 2 (the spotlight's week).
        for (int week = 1; week <= 3; week++) {
            jdbc.update("""
                    insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                    values (?, ?, ?, 1, 90, '{"it-014-w1": 12}'::jsonb)
                    """, leagueId, SEASON, week);
        }
        jdbc.update("""
                insert into league_week_fetch (league_id, kind, week, fetched_at, final)
                values (?, 'POINTS', 1, now(), true)
                """, leagueId);
        for (int week = 2; week <= 3; week++) {
            jdbc.update("""
                    insert into league_week_fetch (league_id, kind, week, fetched_at, final)
                    values (?, 'POINTS', ?, now(), false)
                    """, leagueId, week);
        }
        for (int week = 1; week <= 3; week++) {
            jdbc.update("""
                    insert into sport_week_stats (sport, season, week, fetched_at, final)
                    values ('nfl', ?, ?, now(), ?)
                    """, SEASON, week, week <= 2);
        }

        jdbc.update("""
                insert into player (sport, sleeper_id, name, positions, team, years_exp)
                values ('nfl', 'it-014-w1', 'Week Vet', '{WR}', 'TST', 4)
                """);
        jdbc.update("""
                insert into player (sport, sleeper_id, name, positions, team, years_exp)
                values ('nfl', 'it-014-w2', 'Week Rookie', '{WR}', 'TST', 0)
                """);
        for (String pid : List.of("it-014-w1", "it-014-w2")) {
            jdbc.update("""
                    insert into player_game (sport, season, week, sleeper_player_id, game_id, game_date,
                                             opponent, is_away, stats)
                    values ('nfl', ?, 2, ?, ?, '2020-01-05'::date, 'OPP', false, '{"rec": 8}'::jsonb)
                    """, SEASON, pid, "it-014-wg-" + pid);
        }
        seeded = true;
    }

    private long nflLeague(String sleeperId, long managerId, int season) {
        long id = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, scoring_json, roster_positions)
                values ('nfl', ?, ?, 'IT 014 NFL', 2, '{"rec": 1.0}'::jsonb, '{QB,RB,WR,TE,BN}')
                returning id
                """, Long.class, season, sleeperId);
        leagueMembers.upsert(id, managerId, true, "Team X");
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 1)", id, managerId);
        return id;
    }

    @AfterAll
    static void tearDownAll() {
        cleanUp(new JdbcTemplate(new DriverManagerDataSource(JDBC_URL, USER, PASSWORD)));
        seeded = false;
    }

    private static void cleanUp(JdbcTemplate jdbc) {
        jdbc.update("delete from league where sleeper_id in (?, ?)", LEAGUE, EMPTY_LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id = ?", USER_X);
        jdbc.update("delete from player_game where sport = 'nfl' and season = ?", SEASON);
        jdbc.update("delete from sport_week_stats where sport = 'nfl' and season in (?, ?)", SEASON, EMPTY_SEASON);
        jdbc.update("delete from player where sport = 'nfl' and sleeper_id like 'it-014-%'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @Test
    void thePeriodWeekIsTheNewestFinalStatsWeekNotTheWeeklyReportWeekAndFootballHasNoTopOfNight() {
        seedOnce();
        ResponseEntity<Map<String, Object>> res = spotlight.playerSpotlight(LEAGUE, USER_X);
        assertEquals(200, res.getStatusCode().value());
        Map<String, Object> body = res.getBody();

        Map<String, Object> period = map(body.get("period"));
        assertEquals("WEEK", period.get("kind"));
        Object reportWeek = GoldenJson.wire(weekly.weeklyReport(LEAGUE, 0, USER_X).getBody()).get("week");
        assertEquals(1, reportWeek, "the league's own weeks are final only through 1");
        assertEquals(2, period.get("week"), "stats weeks 1-2 are final and 3 is not: the newest final is 2");
        assertEquals(true, period.get("weekFinal"));

        assertEquals(false, body.get("playersPlayMultiplePerPeriod"));
        assertFalse(body.containsKey("topOfNight"), "football omits the key entirely");
    }

    @Test
    void footballRookieWatchReadsTheWeekRows() {
        seedOnce();
        Map<String, Object> body = spotlight.playerSpotlight(LEAGUE, USER_X).getBody();
        List<Map<String, Object>> rookies = list(map(body.get("rookieWatch")).get("entries"));
        assertEquals(List.of("it-014-w2"), rookies.stream().map(e -> e.get("playerId")).toList());
        assertEquals(8.0, rookies.get(0).get("points"));
        assertEquals(false, map(rookies.get(0).get("ownership")).get("rostered"));
    }

    @Test
    void aLeagueWithNoScoredWeekHasNoWeekScored() {
        seedOnce();
        Map<String, Object> body = spotlight.playerSpotlight(EMPTY_LEAGUE, USER_X).getBody();
        assertNull(body.get("period"));
        assertEquals("NO_WEEK_SCORED", body.get("periodUnavailable"));
        assertEquals("NO_PERIOD", map(body.get("rookieWatch")).get("unavailable"));
    }
}
