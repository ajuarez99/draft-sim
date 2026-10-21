package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/042 US3 T015: the real HTTP surface of {@code /lineup-check}, through the app's own ObjectMapper and
 * the real membership scoping. Sleeper is stubbed (the live {@code /rosters} call is the one thing a test
 * cannot reach); the ESPN refusal is {@code LeagueAvailabilityIT}'s walk of {@code LeagueAvailability.ROUTES},
 * which now includes {@code lineup-check}. Uses a throwaway NBA season (2096). SKIPS when the local Postgres
 * is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LineupCheckControllerMvcIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE = "it-lc-league";
    private static final String ME = "it-lc-me";
    private static final String STRANGER = "it-lc-stranger";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping lineup check MVC IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private SportScheduleRepository schedules;
    @MockitoBean private SleeperClient sleeper;

    @BeforeEach
    void setUp() {
        tearDown();
        long me = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id", Long.class, ME, "it-me");
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, ?)", STRANGER, "it-stranger");
        long leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions, settings_json)
                values ('nba', 2096, ?, 'IT Lineup', 2, 'in_season', '{PG,SG,BN}'::text[], '{"leg": 1}'::jsonb)
                returning id
                """, Long.class, LEAGUE);
        leagueMembers.upsert(leagueId, me, false, "Lineup Tank");
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 4)", leagueId, me);
        player("it-lc-out", "Olly Out", "PG", "Out");
        player("it-lc-ok", "Opal Ok", "SG", null);
        player("it-lc-b1", "Bea Bench", "PG", null);
        player("it-lc-b2", "Abe Bench", "PG", null);
        schedules.replaceSeason("nba", 2096, List.of(
                new SportSchedule.Game("g1", 1, LocalDate.parse("2096-10-20"), "pre_game", "ATL", "BOS"),
                new SportSchedule.Game("g2", 1, LocalDate.parse("2096-10-22"), "pre_game", "ATL", "CHI")),
                OffsetDateTime.of(2026, 10, 5, 18, 2, 11, 0, ZoneOffset.UTC));
    }

    private void player(String sleeperId, String name, String position, String injury) {
        jdbc.update("insert into player (sport, sleeper_id, name, positions, team, injury_status) "
                + "values ('nba', ?, ?, ?::text[], 'ATL', ?)", sleeperId, name, "{" + position + "}", injury);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from sport_schedule where sport = 'nba' and season = 2096");
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it-lc-%'");
        jdbc.update("delete from player where sleeper_id like 'it-lc-%'");
    }

    @Test
    void isA404WithNoHeaderAndForAStranger() throws Exception {
        mvc.perform(get("/api/leagues/" + LEAGUE + "/lineup-check")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/lineup-check").header("X-Sleeper-User", ""))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/lineup-check").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMemberGetsTheFlagWithItsBenchOptionsAndTheContractShape() throws Exception {
        when(sleeper.rosters(anyString())).thenReturn(List.of(
                Map.of("roster_id", 9, "owner_id", "someone-else", "players", List.of("it-lc-b1"),
                        "starters", List.of("0", "0")),
                Map.of("roster_id", 4, "owner_id", ME,
                        "players", List.of("it-lc-out", "it-lc-ok", "it-lc-b1", "it-lc-b2"),
                        "starters", List.of("it-lc-out", "it-lc-ok"))));
        String body = mvc.perform(get("/api/leagues/" + LEAGUE + "/lineup-check").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").value((Object) null))
                .andExpect(jsonPath("$.week").value(1))
                .andExpect(jsonPath("$.gameCheck").value(true))
                .andExpect(jsonPath("$.flags.length()").value(1))
                .andExpect(jsonPath("$.flags[0].slot").value("PG"))
                .andExpect(jsonPath("$.flags[0].player.sleeperPlayerId").value("it-lc-out"))
                .andExpect(jsonPath("$.flags[0].reason").value("OUT"))
                .andExpect(jsonPath("$.flags[0].injuryStatus").value("Out"))
                .andExpect(jsonPath("$.flags[0].optionsOrder").value("NAME"))
                // No box scores for 2096, so no averages: the options are alphabetical and say they have none.
                .andExpect(jsonPath("$.flags[0].options[0].player.name").value("Abe Bench"))
                .andExpect(jsonPath("$.flags[0].options[1].player.name").value("Bea Bench"))
                .andExpect(jsonPath("$.flags[0].options[0].gamesLeft").value(2))
                .andExpect(jsonPath("$.flags[0].options[0].fpPerGame").value((Object) null))
                .andExpect(jsonPath("$.flags[0].options[0].fpGames").value(0))
                .andReturn().getResponse().getContentAsString();
        System.out.println("LINEUP-CHECK-JSON " + body);
    }

    @Test
    void aFailedSleeperCallIsA200WithRostersFailed() throws Exception {
        when(sleeper.rosters(anyString())).thenThrow(new IllegalStateException("sleeper is down"));
        mvc.perform(get("/api/leagues/" + LEAGUE + "/lineup-check").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("ROSTERS_FAILED"))
                .andExpect(jsonPath("$.flags.length()").value(0));
    }
}
