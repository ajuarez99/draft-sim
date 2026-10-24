package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.BoardRepository;
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
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 046 T023: the real HTTP surface of {@code GET /api/leagues/{id}/adp-compare}, through the app's own
 * ObjectMapper and the real membership scoping and Postgres, so the JSON the frontend receives is what is
 * pinned here: all three sources always present, nullable fields serialized as null (a {@code Map.of} on this
 * path would have thrown), an unmatched Yahoo player as a row with a null player id, the cost curve floored
 * at $1 and sorted, and ESPN leagues getting the compare half with no draft.
 *
 * <p>Seeds throwaway NBA 2093 rows and deletes them. SKIPS when Postgres is unreachable, so the caller must
 * read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdpCompareMvcIT {

    private static final String LEAGUE = "it046-cmp-league";
    private static final String ESPN_LEAGUE = "it046-cmp-espn";
    private static final String NFL_LEAGUE = "it046-cmp-nfl";
    /**
     * Far-future so these captures are the LATEST for every source (the page reads the newest), and so a
     * seed can never replace a real day's capture in a shared database.
     */
    private static final LocalDate SEED_DAY = LocalDate.of(2093, 1, 1);
    private static final String ME = "it046-cmp-me";
    private static final String STRANGER = "it046-cmp-stranger";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping adp-compare MVC IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private BoardRepository boards;

    private long leagueId;
    private long p1, p2, p3;

    @BeforeEach
    void setUp() {
        tearDown();
        long me = jdbc.queryForObject("insert into manager (sleeper_user_id, display_name) values (?, 'it me') returning id",
                Long.class, ME);
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, 'it stranger')", STRANGER);
        leagueId = league(LEAGUE, "nba", "SLEEPER");
        leagueMembers.upsert(leagueId, me, false, "Team Me");
        long espn = league(ESPN_LEAGUE, "nba", "ESPN");
        leagueMembers.upsert(espn, me, false, "Team Me");
        long nfl = league(NFL_LEAGUE, "nfl", "SLEEPER");
        leagueMembers.upsert(nfl, me, false, "Team Me");
        p1 = player("p1", "IT Cmp One", "{PG}", 3);
        p2 = player("p2", "IT Cmp Two", "{C,PF}", 0);
        p3 = player("p3", "IT Cmp Three", "{SF}", 7);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id in (?, ?, ?)", LEAGUE, ESPN_LEAGUE, NFL_LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it046-cmp-%'");
        jdbc.update("delete from player where sleeper_id like 'it046-cmp-%'");   // cascades the snapshot rows
        jdbc.update("delete from adp_unmatched where name like 'IT046 %'");
    }

    private long league(String sleeperId, String sport, String platform) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, platform)
                values (?, 2093, ?, 'IT compare', 2, ?) returning id""", Long.class, sport, sleeperId, platform);
    }

    private long player(String tag, String name, String positions, int yearsExp) {
        return jdbc.queryForObject("""
                insert into player (sport, sleeper_id, name, positions, team, years_exp)
                values ('nba', ?, ?, ?::text[], 'BOS', ?) returning id""",
                Long.class, "it046-cmp-" + tag, name, positions, yearsExp);
    }

    private void draft(long leagueId, String sleeperDraftId, String type, Integer budget) {
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, slot_to_manager, budget)
                values (?, ?, 2093, 23, 10, ?, 'complete', '{}'::jsonb, ?)""", leagueId, sleeperDraftId, type, budget);
    }

    private void seedCaptures() {
        LocalDate today = SEED_DAY;
        boards.saveCapture(Sport.NBA, "sleeper_dynasty", today, "dynasty", List.of(
                new BoardRepository.CaptureRow(p1, 5.0, null, null),
                new BoardRepository.CaptureRow(p2, 80.0, null, null)), List.of());
        boards.saveCapture(Sport.NBA, "sleeper_redraft", today, "redraft", List.of(
                new BoardRepository.CaptureRow(p1, 6.0, null, null)), List.of());
        boards.saveCapture(Sport.NBA, "yahoo", today, "redraft", List.of(
                new BoardRepository.CaptureRow(p1, 4.0, 60.0, 1.0),
                new BoardRepository.CaptureRow(p3, 100.0, 0.4, 0.3)),
                List.of(new BoardRepository.UnmatchedRow("IT046 Unmatched Guy", "LAL", "PG,SG", 50.0, null, 0.9)));
    }

    @Test
    void aStrangerOrNoIdentityIs404() throws Exception {
        mvc.perform(get("/api/leagues/" + LEAGUE + "/adp-compare")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/adp-compare").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/no-such-league/adp-compare").header("X-Sleeper-User", ME))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFootballLeagueHasNoCompareSources() throws Exception {
        mvc.perform(get("/api/leagues/" + NFL_LEAGUE + "/adp-compare").header("X-Sleeper-User", ME))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourcesAlwaysListAllThreeKeysEvenWithNoCaptureAtAll() throws Exception {
        // Make sure no other capture is "latest": this IT's own DB state is the only thing under test, so run
        // against whatever the scratch DB holds and assert only the shape that is true either way.
        mvc.perform(get("/api/leagues/" + LEAGUE + "/adp-compare").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sport").value("nba"))
                .andExpect(jsonPath("$.sources", hasSize(3)))
                .andExpect(jsonPath("$.sources[*].key").value(contains("sleeper_dynasty", "sleeper_redraft", "yahoo")))
                .andExpect(jsonPath("$.sources[0].scoring").value("dynasty"))
                .andExpect(jsonPath("$.sources[2].scoring").value("redraft"))
                .andExpect(jsonPath("$.sources[2].minPercentDrafted").value(0.5))
                .andExpect(jsonPath("$.draft").value(nullValue()));
    }

    @Test
    void nullableFieldsSerializeAsNullAndAnUnmatchedYahooPlayerHasNoPlayerId() throws Exception {
        seedCaptures();
        try {
            mvc.perform(get("/api/leagues/" + LEAGUE + "/adp-compare").header("X-Sleeper-User", ME))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sources[0].capturedOn").value(SEED_DAY.toString()))
                    .andExpect(jsonPath("$.sources[0].ranked").value(2))
                    .andExpect(jsonPath("$.sources[1].ranked").value(1))
                    // ranked counts what Yahoo ranked: 2 matched + 1 unmatched.
                    .andExpect(jsonPath("$.sources[2].ranked").value(3))
                    .andExpect(jsonPath("$.sources[2].unmatched").value(1))
                    .andExpect(jsonPath("$.sources[2].deepestAdp").value(100.0))
                    .andExpect(jsonPath("$.sources[0].unmatched").value(nullValue()))
                    // Sorted dynasty asc, nulls last: p1 (5), p2 (80), then the Yahoo-only rows by Yahoo ADP (unmatched 50, p3 100).
                    .andExpect(jsonPath("$.players[0].name").value("IT Cmp One"))
                    .andExpect(jsonPath("$.players[0].positions").value(contains("PG")))
                    .andExpect(jsonPath("$.players[0].yahooCost").value(60.0))
                    .andExpect(jsonPath("$.players[1].name").value("IT Cmp Two"))
                    .andExpect(jsonPath("$.players[1].redraft").value(nullValue()))
                    .andExpect(jsonPath("$.players[1].yahooAdp").value(nullValue()))
                    .andExpect(jsonPath("$.players[1].yahooPctDrafted").value(nullValue()))
                    .andExpect(jsonPath("$.players[1].yearsExp").value(0))
                    .andExpect(jsonPath("$.players[3].name").value("IT Cmp Three"))
                    .andExpect(jsonPath("$.players[3].dynasty").value(nullValue()))
                    .andExpect(jsonPath("$.players[2].name").value("IT046 Unmatched Guy"))
                    .andExpect(jsonPath("$.players[2].playerId").value(nullValue()))
                    .andExpect(jsonPath("$.players[2].sleeperId").value(nullValue()))
                    .andExpect(jsonPath("$.players[2].positions").value(contains("PG", "SG")))
                    .andExpect(jsonPath("$.players[2].yahooAdp").value(50.0))
                    .andExpect(jsonPath("$.players[2].yahooCost").value(nullValue()))
                    // 60.0, p3's 0.4 floored to 1.0; the unmatched player's null cost is not in the curve.
                    .andExpect(jsonPath("$.costCurve").value(contains(60.0, 1.0)));
        } finally {
            jdbc.update("delete from adp_snapshot where source in ('sleeper_dynasty','sleeper_redraft','yahoo') and captured_on = ? "
                    + "and player_id in (?, ?, ?)", SEED_DAY, p1, p2, p3);
        }
    }

    @Test
    void theDraftObjectCarriesTypeShapeAndANullableBudget() throws Exception {
        draft(leagueId, "it046-cmp-draft-auction", "auction", 200);
        mvc.perform(get("/api/leagues/" + LEAGUE + "/adp-compare").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.sleeperDraftId").value("it046-cmp-draft-auction"))
                .andExpect(jsonPath("$.draft.type").value("auction"))
                .andExpect(jsonPath("$.draft.status").value("complete"))
                .andExpect(jsonPath("$.draft.teams").value(10))
                .andExpect(jsonPath("$.draft.rounds").value(23))
                .andExpect(jsonPath("$.draft.budget").value(200))
                .andExpect(jsonPath("$.draft.teamsState.length()").value(10))
                .andExpect(jsonPath("$.draft.sales.length()").value(0));

        jdbc.update("delete from draft where sleeper_draft_id = 'it046-cmp-draft-auction'");
        draft(leagueId, "it046-cmp-draft-snake", "snake", null);
        mvc.perform(get("/api/leagues/" + LEAGUE + "/adp-compare").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.type").value("snake"))
                .andExpect(jsonPath("$.draft.budget").value(nullValue()))
                // Omitted, not empty: a snake page must not be able to render an empty budget grid.
                .andExpect(jsonPath("$.draft.teamsState").doesNotExist())
                .andExpect(jsonPath("$.draft.sales").doesNotExist());
    }

    @Test
    void anEspnNbaLeagueGetsTheCompareHalfWithNoDraftAndNoPlatformRefusal() throws Exception {
        long espn = jdbc.queryForObject("select id from league where sleeper_id = ?", Long.class, ESPN_LEAGUE);
        draft(espn, "it046-cmp-espn-draft", "snake", null);

        mvc.perform(get("/api/leagues/" + ESPN_LEAGUE + "/adp-compare").header("X-Sleeper-User", ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources", hasSize(3)))
                .andExpect(jsonPath("$.draft").value(nullValue()));
    }
}
