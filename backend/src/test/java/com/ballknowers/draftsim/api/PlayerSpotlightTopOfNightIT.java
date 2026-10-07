package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
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
 * Spec 014 T018: the player spotlight end to end for a basketball league, against real Postgres
 * (the SQL in {@code forDate}, the date bind, the ownership read) and the real controllers.
 *
 * <p>Seeds a throwaway NBA season (2099) so it cannot touch real data. Game dates are in the past
 * (2020) on purpose: night completeness is judged against the real clock, so a date in the
 * future could never be complete. SKIPS when the local Postgres is unreachable; read the skip count.
 */
@SpringBootTest
class PlayerSpotlightTopOfNightIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE = "it-014-nba-league";
    private static final String USER_X = "it-014-user-x";
    private static final String USER_Y = "it-014-user-y";
    private static final int SEASON = 2099;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping PlayerSpotlightTopOfNight IT");
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
        cleanUp();

        long leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, scoring_json, roster_positions)
                values ('nba', ?, ?, 'IT 014 NBA', 2, '{"pts": 1.0, "reb": 1.2, "ast": 1.5}'::jsonb,
                        '{PG,SG,SF,PF,C,BN}')
                returning id
                """, Long.class, SEASON, LEAGUE);
        long x = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, USER_X, "IT X");
        long y = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, USER_Y, "IT Y");
        leagueMembers.upsert(leagueId, x, true, "Team X");
        leagueMembers.upsert(leagueId, y, false, "Team Y");
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 1)", leagueId, x);
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 2)", leagueId, y);

        // Week 1: roster 1 (X) has p1 and p2, roster 2 (Y) has p3. p4 and p5 are free agents.
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, ?, 1, 1, 100, '{"it-014-p1": 25, "it-014-p2": 40}'::jsonb)
                """, leagueId, SEASON);
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points)
                values (?, ?, 1, 2, 80, '{"it-014-p3": 31}'::jsonb)
                """, leagueId, SEASON);

        player("it-014-p1", "Player One", 3);
        player("it-014-p2", "Player Two", 3);
        player("it-014-p3", "Player Three", 3);
        player("it-014-p4", "Free Agent Star", 5);
        player("it-014-p5", "Free Agent Rookie", 0);

        // Night A (2020-01-14): p1 only. Night B (2020-01-15, the later one): two games.
        game("it-014-p1", "it-014-gA", "2020-01-14", 50);
        game("it-014-p1", "it-014-gB1", "2020-01-15", 25);
        game("it-014-p2", "it-014-gB1", "2020-01-15", 40);
        game("it-014-p3", "it-014-gB2", "2020-01-15", 31);
        game("it-014-p4", "it-014-gB2", "2020-01-15", 90);
        game("it-014-p5", "it-014-gB2", "2020-01-15", 20);

        // Fetched after both nights' 10:00 UTC cutoffs.
        jdbc.update("""
                insert into sport_week_stats (sport, season, week, fetched_at, final)
                values ('nba', ?, 1, '2020-01-16T12:00:00Z', true)
                """, SEASON);
        seeded = true;
    }

    private void player(String id, String name, int yearsExp) {
        jdbc.update("""
                insert into player (sport, sleeper_id, name, positions, team, years_exp)
                values ('nba', ?, ?, '{PG}', 'TST', ?)
                """, id, name, yearsExp);
    }

    private void game(String playerId, String gameId, String date, int pts) {
        jdbc.update("""
                insert into player_game (sport, season, week, sleeper_player_id, game_id, game_date,
                                         opponent, is_away, stats)
                values ('nba', ?, 1, ?, ?, ?::date, 'OPP', false, ?::jsonb)
                """, SEASON, playerId, gameId, date, "{\"pts\": " + pts + "}");
    }

    @AfterAll
    static void tearDownAll() {
        cleanUp(new JdbcTemplate(new DriverManagerDataSource(JDBC_URL, USER, PASSWORD)));
        seeded = false;
    }

    private void cleanUp() {
        cleanUp(jdbc);
    }

    private static void cleanUp(JdbcTemplate jdbc) {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id in (?, ?)", USER_X, USER_Y);
        jdbc.update("delete from player_game where sport = 'nba' and season = ?", SEASON);
        jdbc.update("delete from sport_week_stats where sport = 'nba' and season = ?", SEASON);
        jdbc.update("delete from player where sport = 'nba' and sleeper_id like 'it-014-%'");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private Map<String, Object> spotlightBody(String user) {
        ResponseEntity<Map<String, Object>> res = spotlight.playerSpotlight(LEAGUE, user);
        assertEquals(200, res.getStatusCode().value());
        return res.getBody();
    }

    @Test
    void theLatestCompleteNightIsRankedAmongRosteredPlayersWithOwnersAndIsMe() {
        seedOnce();
        Map<String, Object> body = spotlightBody(USER_X);

        assertEquals(true, body.get("applies"));
        assertEquals(true, body.get("playersPlayMultiplePerPeriod"));
        assertEquals("2020-01-15", map(body.get("period")).get("date"));
        assertEquals(2, map(body.get("period")).get("gamesCount"));
        assertNull(body.get("laterNightInProgress"));

        Map<String, Object> top = map(body.get("topOfNight"));
        assertNull(top.get("unavailable"));
        List<Map<String, Object>> entries = list(top.get("entries"));
        // p4 (90) is a free agent and p1's 50 was the earlier night: neither may appear.
        assertEquals(List.of("it-014-p2", "it-014-p3", "it-014-p1"),
                entries.stream().map(e -> e.get("playerId")).toList());
        assertEquals(List.of(40.0, 31.0, 25.0), entries.stream().map(e -> e.get("points")).toList());
        assertEquals("Team X", map(entries.get(0).get("ownership")).get("teamName"));
        assertEquals("Team Y", map(entries.get(1).get("ownership")).get("teamName"));
        assertEquals(true, map(entries.get(0).get("ownership")).get("isMe"), "the caller's own player");
        assertEquals(false, map(entries.get(1).get("ownership")).get("isMe"));
    }

    @Test
    void isMeFollowsTheCaller() {
        seedOnce();
        List<Map<String, Object>> entries = list(map(spotlightBody(USER_Y).get("topOfNight")).get("entries"));
        assertEquals(false, map(entries.get(0).get("ownership")).get("isMe"));
        assertEquals(true, map(entries.get(1).get("ownership")).get("isMe"));
    }

    @Test
    void rookieWatchIncludesAnUnrosteredRookieAndNoVeteran() {
        seedOnce();
        List<Map<String, Object>> rookies = list(map(spotlightBody(USER_X).get("rookieWatch")).get("entries"));
        // years_exp == 0 are p5 (free agent) only; p1..p3 have 3 years, p4 has 5.
        assertEquals(List.of("it-014-p5"), rookies.stream().map(e -> e.get("playerId")).toList());
        assertEquals(false, map(rookies.get(0).get("ownership")).get("rostered"));
    }

    /** SC-003: one game's points must be the same number on the spotlight and the Weekly Report. */
    @Test
    void spotlightPointsEqualTheWeeklyReportBestNightsForTheSameGame() {
        seedOnce();
        List<Map<String, Object>> top = list(map(spotlightBody(USER_X).get("topOfNight")).get("entries"));

        ResponseEntity<?> report = weekly.weeklyReport(LEAGUE, 1, USER_X);
        assertEquals(200, report.getStatusCode().value());
        List<Map<String, Object>> nights = list(GoldenJson.wire(report.getBody()).get("bestNights"));
        assertFalse(nights.isEmpty());

        int compared = 0;
        for (Map<String, Object> entry : top) {
            for (Map<String, Object> night : nights) {
                if (entry.get("playerId").equals(night.get("playerId"))
                        && "2020-01-15".equals(night.get("date"))) {
                    assertEquals(night.get("points"), entry.get("points"),
                            "points for " + entry.get("playerId"));
                    compared++;
                }
            }
        }
        assertEquals(3, compared, "all three spotlight players appear in the report's best nights");
    }

    @Test
    void aStrangerGets404() {
        seedOnce();
        assertEquals(404, spotlight.playerSpotlight(LEAGUE, "it-014-nobody").getStatusCode().value());
        assertEquals(Sport.NBA.code(), spotlightBody(USER_X).get("sport"));
    }
}
