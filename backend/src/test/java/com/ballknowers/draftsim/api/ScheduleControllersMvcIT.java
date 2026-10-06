package com.ballknowers.draftsim.api;

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
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/017-nba-schedule-grid T021/T037: the real HTTP surface of {@code /schedule} and
 * {@code /next-matchup}, through the app's own ObjectMapper and the real membership scoping, so the
 * JSON shape ({@code fetchedAt}/{@code firstDate} as ISO strings, {@code games} as a number array,
 * nullable fields as null) is what the frontend actually receives. Uses a throwaway NBA season
 * (2098) so it can't touch real schedule rows. SKIPS when the local Postgres is unreachable, so the
 * caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleControllersMvcIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE = "it-sched-league";
    private static final String OLDER_LEAGUE = "it-sched-league-2097";
    private static final String ME = "it-sched-me";
    private static final String RIVAL = "it-sched-rival";
    private static final String STRANGER = "it-sched-stranger";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping schedule controllers MVC IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private SportScheduleRepository schedules;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        long me = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name, avatar_id) values (?, ?, ?) returning id",
                Long.class, ME, "it-me", "abc123");
        long rival = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, RIVAL, "it-rival");
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, ?)", STRANGER, "it-stranger");
        // The older season of the chain: complete, leg 21, scored. The URL names the 2098 league.
        jdbc.update("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, settings_json)
                values ('nba', 2097, ?, 'IT Sched', 2, 'complete',
                        '{"leg": 21, "playoff_week_start": 19, "playoff_teams": 6, "playoff_round_type": 0}'::jsonb)
                """, OLDER_LEAGUE);
        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, previous_league_id, status,
                                    settings_json)
                values ('nba', 2098, ?, 'IT Sched', 2, ?, 'in_season',
                        '{"leg": 1, "playoff_week_start": 20, "playoff_teams": 6, "playoff_round_type": 0}'::jsonb)
                returning id
                """, Long.class, LEAGUE, OLDER_LEAGUE);
        leagueMembers.upsert(leagueId, me, false, "Dunk Tank");
        leagueMembers.upsert(leagueId, rival, false, null);
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 4)", leagueId, me);
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 9)", leagueId, rival);
        jdbc.update("insert into league_matchup (league_id, season, week, roster_id, matchup_id) values (?, 2098, 1, 4, 1)",
                leagueId);
        jdbc.update("insert into league_matchup (league_id, season, week, roster_id, matchup_id) values (?, 2098, 1, 9, 1)",
                leagueId);
        schedules.replaceSeason("nba", 2098, List.of(
                new SportSchedule.Game("g1", 1, LocalDate.parse("2098-10-20"), "pre_game", "ATL", "BOS"),
                new SportSchedule.Game("g2", 1, LocalDate.parse("2098-10-22"), "pre_game", "ATL", "CHI"),
                new SportSchedule.Game("g3", 2, LocalDate.parse("2098-10-27"), "pre_game", "BOS", "CHI"),
                new SportSchedule.Game("g4", 2, LocalDate.parse("2098-10-28"), "postponed", "ATL", "CHI")),
                OffsetDateTime.of(2026, 10, 5, 18, 2, 11, 0, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from sport_schedule where sport = 'nba' and season = 2098");
        jdbc.update("delete from league where sleeper_id in (?, ?)", LEAGUE, OLDER_LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it-sched-%'");
    }

    @Test
    void scheduleIs404WithNoHeaderAndForAStranger() throws Exception {
        mvc.perform(get("/api/leagues/" + LEAGUE + "/schedule")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/schedule").header("X-Sleeper-User", ""))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/schedule").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
    }

    @Test
    void nextMatchupIs404WithNoHeaderAndForAStranger() throws Exception {
        mvc.perform(get("/api/leagues/" + LEAGUE + "/next-matchup")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/next-matchup").header("X-Sleeper-User", ""))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/next-matchup").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
    }

    @Test
    void scheduleJsonShapeMatchesTheContract() throws Exception {
        String body = mvc.perform(get("/api/leagues/" + LEAGUE + "/schedule").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sport").value("nba"))
                .andExpect(jsonPath("$.season").value(2098))          // F1: the URL's season, not 2097
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.fetchedAt", matchesPattern("2026-10-05T18:02:11(\\.0+)?Z")))
                .andExpect(jsonPath("$.currentWeek").value(1))        // 2098's leg, not 2097's 21
                .andExpect(jsonPath("$.lastLeagueWeek").value(22))
                .andExpect(jsonPath("$.seasonOver").value(false))
                .andExpect(jsonPath("$.weeks[0].week").value(1))
                .andExpect(jsonPath("$.weeks[0].firstDate").value("2098-10-20"))
                .andExpect(jsonPath("$.weeks[0].lastDate").value("2098-10-22"))
                .andExpect(jsonPath("$.playoff.startWeek").value(20))
                .andExpect(jsonPath("$.playoff.endWeek").value(22))
                .andExpect(jsonPath("$.playoff.reason").doesNotExist())
                .andExpect(jsonPath("$.teams[0].team").value("ATL"))
                .andExpect(jsonPath("$.teams[0].games", contains(2, 0)))
                .andExpect(jsonPath("$.teams[0].seasonTotal").value(2))
                .andExpect(jsonPath("$.teams[1].team").value("BOS"))
                .andExpect(jsonPath("$.teams[1].games", contains(1, 1)))
                .andExpect(jsonPath("$.teams[2].games", contains(1, 1)))
                .andExpect(jsonPath("$.excluded.postponed").value(1))
                .andExpect(jsonPath("$.excluded.canceled").value(0))
                .andReturn().getResponse().getContentAsString();
        System.out.println("SCHEDULE-JSON " + body);
    }

    @Test
    void nextMatchupJsonShapeMatchesTheContract() throws Exception {
        String body = mvc.perform(get("/api/leagues/" + LEAGUE + "/next-matchup").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sport").value("nba"))
                .andExpect(jsonPath("$.season").value(2098))
                .andExpect(jsonPath("$.week").value(1))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.me.rosterId").value(4))
                .andExpect(jsonPath("$.me.teamName").value("Dunk Tank"))
                .andExpect(jsonPath("$.me.username").value("it-me"))
                .andExpect(jsonPath("$.me.avatarId").value("abc123"))
                .andExpect(jsonPath("$.opponent.rosterId").value(9))
                .andExpect(jsonPath("$.opponent.teamName").doesNotExist())
                .andExpect(jsonPath("$.opponent.username").value("it-rival"))
                .andReturn().getResponse().getContentAsString();
        System.out.println("NEXT-MATCHUP-JSON " + body);
        // Not-out-yet week: the unavailable shape serializes me/opponent as null, not absent keys.
        jdbc.update("delete from league_matchup where league_id = ?", leagueId);
        String unavailable = mvc.perform(get("/api/leagues/" + LEAGUE + "/next-matchup").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason", endsWith("shortly before the week starts.")))
                .andExpect(jsonPath("$.me").value((Object) null))
                .andExpect(jsonPath("$.opponent").value((Object) null))
                .andReturn().getResponse().getContentAsString();
        System.out.println("NEXT-MATCHUP-UNAVAILABLE-JSON " + unavailable);
    }
}
