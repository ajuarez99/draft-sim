package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.store.LeagueMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 044 T016: the real HTTP surface of {@code /lock-ins}, through the app's own ObjectMapper, the real
 * membership scoping and the real SQL (AGENTS.md bug classes 2, 3 and 6). Seeds a throwaway NBA season (2095) with
 * one lock and a football league, and deletes it all. SKIPS when the local Postgres is unreachable, so the caller
 * must read the skip count.
 *
 * <pre>
 * League scoring pts x 1, game_mode 1, one roster owned by "me". p1 plays 30 (Jan 1) then 20 (Jan 2) in week 1 and
 * the roster is credited 30: a lock of the first game. No earlier game, so there is no stand-in baseline.
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LockInControllerIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final int SEASON = 2095;
    private static final String NBA = "it-044-nba";
    private static final String NFL = "it-044-nfl";
    private static final String ME = "it-044-me";
    private static final String STRANGER = "it-044-stranger";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping lock-in IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;

    @BeforeEach
    void setUp() {
        tearDown();
        long me = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id", Long.class, ME, "it-me");
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, ?)", STRANGER, "it-stranger");

        long nba = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, scoring_json, settings_json)
                values ('nba', ?, ?, 'IT lock-in', 2, 'complete', '{"pts": 1.0}'::jsonb, '{"game_mode": 1}'::jsonb)
                returning id
                """, Long.class, SEASON, NBA);
        long nfl = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, scoring_json)
                values ('nfl', ?, ?, 'IT football', 2, 'complete', '{"rec": 1.0}'::jsonb)
                returning id
                """, Long.class, SEASON, NFL);
        leagueMembers.upsert(nba, me, false, "Lock Tank");
        leagueMembers.upsert(nfl, me, false, "Gridiron");
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 1)", nba, me);

        game("TEAM_AAA", "t1", "2095-01-01", "BBB", "{\"pts\": 100}");
        game("TEAM_BBB", "t1", "2095-01-01", "AAA", "{\"pts\": 90}");
        game("it-044-p1", "g1", "2095-01-01", "BBB", "{\"sp\": 1800, \"pts\": 30}");
        game("it-044-p1", "g2", "2095-01-02", "BBB", "{\"sp\": 1800, \"pts\": 20}");
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points, starters)
                values (?, ?, 1, 1, 30, '{"it-044-p1": 30.0}'::jsonb, '["it-044-p1"]'::jsonb)
                """, nba, SEASON);
    }

    private void game(String player, String gameId, String date, String opponent, String stats) {
        jdbc.update("insert into player_game (sport, season, week, sleeper_player_id, game_id, game_date, opponent, stats) "
                + "values ('nba', ?, 1, ?, ?, ?::date, ?, ?::jsonb)", SEASON, player, gameId, date, opponent, stats);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from player_game where sport = 'nba' and season = ?", SEASON);
        jdbc.update("delete from league where sleeper_id in (?, ?)", NBA, NFL);
        jdbc.update("delete from manager where sleeper_user_id like 'it-044-%'");
    }

    @Test
    void isA404ForAnUnknownLeagueAndForACallerWhoCannotSeeIt() throws Exception {
        mvc.perform(get("/api/leagues/no-such-league/lock-ins").header("X-Sleeper-User", ME))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + NBA + "/lock-ins")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + NBA + "/lock-ins").header("X-Sleeper-User", ""))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + NBA + "/lock-ins").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMemberGetsAMeasuredReportInTheContractShape() throws Exception {
        String body = mvc.perform(get("/api/leagues/" + NBA + "/lock-ins").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sport").value("nba"))
                .andExpect(jsonPath("$.season").value(SEASON))
                .andExpect(jsonPath("$.status").value("MEASURED"))
                .andExpect(jsonPath("$.reason").value((Object) null))
                .andExpect(jsonPath("$.minLocks").value(10))
                .andExpect(jsonPath("$.minPriorGames").value(5))
                .andExpect(jsonPath("$.bands.length()").value(4))
                .andExpect(jsonPath("$.weeksCovered").value(1))
                .andExpect(jsonPath("$.totals.multiGame").value(1))
                .andExpect(jsonPath("$.totals.lock").value(1))
                .andExpect(jsonPath("$.totals.noBaselineStandIn").value(1))
                .andExpect(jsonPath("$.totals.noSnapshot").value(1))
                .andExpect(jsonPath("$.managers.length()").value(1))
                .andExpect(jsonPath("$.managers[0].name").value("Lock Tank"))
                .andExpect(jsonPath("$.managers[0].standIn.n").value(0))
                .andExpect(jsonPath("$.managers[0].standIn.median").value((Object) null))
                .andExpect(jsonPath("$.managers[0].projection.n").value(0))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].key").value("it-044-p1"))
                .andExpect(jsonPath("$.locks.length()").value(0))
                .andExpect(jsonPath("$.payoff.STAND_IN.length()").value(15))
                .andExpect(jsonPath("$.payoff.STAND_IN[0].band").value("<0"))
                .andExpect(jsonPath("$.payoff.STAND_IN[0].gamesLeft").value(1))
                .andExpect(jsonPath("$.payoff.STAND_IN[0].n").value(0))
                .andExpect(jsonPath("$.payoff.STAND_IN[0].rateLast").value((Object) null))
                .andExpect(jsonPath("$.payoff.PROJECTION.length()").value(15))
                // The seeded season has no earlier games, so no candidate margin: nothing to learn thresholds from.
                .andExpect(jsonPath("$.thresholds.status").value("NO_HISTORY"))
                .andExpect(jsonPath("$.thresholds.from.length()").value(0))
                .andExpect(jsonPath("$.thresholds.byGamesLeft.length()").value(0))
                .andExpect(jsonPath("$.live").value((Object) null))
                .andExpect(jsonPath("$.caveats.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        System.out.println("LOCK-INS-JSON " + body);
    }

    @Test
    void thePlayerParamNarrowsTheAnswerToThatPlayersLocks() throws Exception {
        mvc.perform(get("/api/leagues/" + NBA + "/lock-ins?player=it-044-p1&live=false").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managers.length()").value(0))
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.locks.length()").value(1))
                .andExpect(jsonPath("$.locks[0].gameId").value("g1"))
                .andExpect(jsonPath("$.locks[0].points").value(30.0))
                .andExpect(jsonPath("$.locks[0].laterGames").value(1))
                .andExpect(jsonPath("$.locks[0].lastGame").value(20.0))
                .andExpect(jsonPath("$.locks[0].beatenLater").value(false))
                .andExpect(jsonPath("$.locks[0].standIn.baseline").value((Object) null))
                .andExpect(jsonPath("$.locks[0].projection").value((Object) null))
                .andExpect(jsonPath("$.locks[0].manager").value("Lock Tank"));
        mvc.perform(get("/api/leagues/" + NBA + "/lock-ins?player=nobody").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(0))
                .andExpect(jsonPath("$.locks.length()").value(0));
    }

    @Test
    void aFootballLeagueAnswersNotNbaWithEmptyArrays() throws Exception {
        mvc.perform(get("/api/leagues/" + NFL + "/lock-ins").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_NBA"))
                .andExpect(jsonPath("$.managers.length()").value(0))
                .andExpect(jsonPath("$.players.length()").value(0))
                .andExpect(jsonPath("$.totals.multiGame").value(0));
    }
}
