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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * specs/030-post-draft-season-view T017: the real HTTP surface of {@code /season-start}, through the
 * app's own ObjectMapper and membership scoping, so the JSON (nullable fields as null, not absent, and
 * no {@code Map.of} null crash when the date and draft id are missing) is what the frontend receives.
 * Sleeper is mocked so nothing leaves the machine. Uses a throwaway NBA season (2098). SKIPS when the
 * local Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeasonStartControllerIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    private static final String LEAGUE = "it-s030-league";
    private static final String BARE_LEAGUE = "it-s030-bare";
    private static final String ME = "it-s030-me";
    private static final String STRANGER = "it-s030-stranger";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping season-start controller IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private SportScheduleRepository schedules;
    @MockitoBean private SleeperClient sleeper;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        when(sleeper.state("nba")).thenReturn(Map.of());
        when(sleeper.rosters(LEAGUE)).thenReturn(List.of());
        long me = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name, avatar_id) values (?, ?, ?) returning id",
                Long.class, ME, "it-me", "abc123");
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, ?)", STRANGER, "it-stranger");
        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, settings_json, roster_positions)
                values ('nba', 2098, ?, 'IT S030', 2, 'in_season',
                        '{"leg": 1, "playoff_week_start": 20, "playoff_teams": 6, "playoff_round_type": 0}'::jsonb,
                        array['PG','SG','G','SF','PF','F','C','UTIL','UTIL','BN','BN'])
                returning id
                """, Long.class, LEAGUE);
        leagueMembers.upsert(leagueId, me, false, "Dunk Tank");
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, 4)", leagueId, me);
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, slot_to_manager)
                values (?, 'it-s030-draft', 2098, 14, 2, 'snake', 'complete', '{}'::jsonb)
                """, leagueId);
        schedules.replaceSeason("nba", 2098, List.of(
                new SportSchedule.Game("g1", 1, LocalDate.parse("2098-10-20"), "pre_game", "ATL", "BOS"),
                new SportSchedule.Game("g2", 2, LocalDate.parse("2098-10-27"), "pre_game", "BOS", "CHI")),
                OffsetDateTime.of(2026, 10, 5, 18, 2, 11, 0, ZoneOffset.UTC));

        // A league with no draft row, no schedule and no sport-state date: every nullable field is null.
        long bare = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, settings_json)
                values ('nba', 2099, ?, 'IT S030 bare', 2, 'in_season', '{"leg": 1}'::jsonb) returning id
                """, Long.class, BARE_LEAGUE);
        leagueMembers.upsert(bare, me, false, null);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from sport_schedule where sport = 'nba' and season in (2098, 2099)");
        jdbc.update("delete from league where sleeper_id in (?, ?)", LEAGUE, BARE_LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it-s030-%'");
    }

    @Test
    void is404WithNoHeaderAndForAStrangerAndForAnUnknownLeague() throws Exception {
        mvc.perform(get("/api/leagues/" + LEAGUE + "/season-start")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/season-start").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/nope/season-start").header("X-Sleeper-User", ME))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCompleteDraftWithNoStoredWeeksIsDraftedUnscoredWithTheFirstWeekFilledIn() throws Exception {
        String body = mvc.perform(get("/api/leagues/" + LEAGUE + "/season-start").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sport").value("nba"))
                .andExpect(jsonPath("$.season").value(2098))
                .andExpect(jsonPath("$.state").value("DRAFTED_UNSCORED"))
                .andExpect(jsonPath("$.firstWeek.week").value(1))
                .andExpect(jsonPath("$.firstWeek.startDate").value("2098-10-20"))
                // No projections stored for 2098, so strength is the honest unavailable shape.
                .andExpect(jsonPath("$.strength.available").value(false))
                .andExpect(jsonPath("$.strength.reasonCode").value("PROJECTIONS_NOT_LOADED"))
                .andExpect(jsonPath("$.strength.unit").value("PER_WEEK"))
                .andExpect(jsonPath("$.strength.top").isEmpty())
                .andExpect(jsonPath("$.strength.me").value((Object) null))
                .andExpect(jsonPath("$.strength.favorite").value((Object) null))
                .andExpect(jsonPath("$.firstWeek.startersWithoutGame").value((Object) null))
                .andExpect(jsonPath("$.draft.sleeperDraftId").value("it-s030-draft"))
                .andExpect(jsonPath("$.draft.grades").value((Object) null))
                .andReturn().getResponse().getContentAsString();
        System.out.println("SEASON-START-JSON " + body);
    }

    @Test
    void oneStoredFinalWeekIsInSeasonAndDropsFirstWeekAndStrength() throws Exception {
        jdbc.update("""
                insert into roster_week_points (league_id, season, week, roster_id, starters_points)
                values (?, 2098, 1, 4, 100.5)
                """, leagueId);

        mvc.perform(get("/api/leagues/" + LEAGUE + "/season-start").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IN_SEASON"))
                .andExpect(jsonPath("$.firstWeek").value((Object) null))
                .andExpect(jsonPath("$.strength").value((Object) null))
                .andExpect(jsonPath("$.draft.sleeperDraftId").value("it-s030-draft"))
                // Grades need a final week with game data; none is stored here, so the summary is null.
                .andExpect(jsonPath("$.draft.grades").value((Object) null));
    }

    @Test
    void aNullStartDateAndANullDraftIdSerializeAsNullNotACrash() throws Exception {
        mvc.perform(get("/api/leagues/" + BARE_LEAGUE + "/season-start").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFTED_UNSCORED"))
                .andExpect(jsonPath("$.firstWeek.week").value(1))
                .andExpect(jsonPath("$.firstWeek.startDate").value((Object) null))
                .andExpect(jsonPath("$.draft.sleeperDraftId").value((Object) null))
                .andExpect(jsonPath("$.draft.grades").value((Object) null));
    }
}
