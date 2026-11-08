package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.engine.LeagueShape;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 056 T023 (review F3, F9, SC-008): basketball and football in one database under the new
 * {@code (sport, season_start)} key. Both synthetic worlds sit on the SAME season numbers (1893..1895), which is
 * exactly the collision the old {@code season_start}-only key could not hold.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SportCoexistenceIT {

    private static final String USER = "it-sportco-user";

    /** A 053-shaped board, as the code wrote it before V46: no dataNotes, no attribution, no totals, no rookiePick. */
    private static final String BOARD_053 = "{\"cards\":[{\"id\":1,\"name\":\"Old Shape\",\"positions\":[\"PG\"],"
            + "\"team\":\"AAA\",\"age\":25,\"rank\":1,\"positionalRank\":1,\"prior\":{\"seasonLabel\":\"1894-95\","
            + "\"games\":60,\"perGame\":{\"pts\":10.5},\"fantasyPoints\":300.0,\"bestOfTwo\":\"1894-95\"}},"
            + "{\"id\":2,\"name\":\"Rookie Shape\",\"positions\":[\"C\"],\"team\":null,\"age\":null,\"rank\":2,"
            + "\"positionalRank\":1,\"prior\":null}],"
            + "\"excludedStats\":{\"stl\":\"not tracked by the league that season\"},\"statCoverage\":{\"pts\":1.0}}";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping sport coexistence IT");
    }

    @Autowired private HistoricalGameService service;
    @Autowired private HistoricalGameRepository games;
    @Autowired private HistoricalDataRepository data;
    @Autowired private NflHistoricalRepository nfl;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void seed() {
        reseed(jdbc, data, nfl);
    }

    private static void reseed(JdbcTemplate jdbc, HistoricalDataRepository data, NflHistoricalRepository nfl) {
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-sportco-%'");
        HistoricalSyntheticSeason.clean(jdbc);   // not sport-scoped: removes both worlds' rows, so reseed both
        NflSyntheticSeason.clean(jdbc);
        NflSyntheticSeason.seed(nfl, jdbc);
        HistoricalSyntheticSeason.seed(data);
    }

    @AfterAll
    static void removeTheMadeUpWorlds() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "draftsim", "draftsim"));
            j.update("delete from historical_game where sleeper_user_id like 'it-sportco-%'");
            NflSyntheticSeason.clean(j);
            HistoricalSyntheticSeason.clean(j);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was seeded either.
        }
    }

    @Test
    void sameSeasonNumberInTwoSportsGivesExactlyOneListRowPerGame() throws Exception {
        long nba = service.create(USER, "NBA", 3, HistoricalSyntheticSeason.S).id();
        long nflGame = service.create(USER, "NFL", 3, NflSyntheticSeason.S).id();
        assertEquals(2, jdbc.queryForObject("select count(*) from historical_season where season_start = ?",
                Integer.class, 1895), "one season row per sport at the same number");

        var list = service.list(USER);
        assertEquals(2, list.size(), "a join that ignored sport would return 4");
        var byId = new java.util.HashMap<Long, HistoricalGameState.GameSummary>();
        list.forEach(g -> byId.put(g.id(), g));
        assertEquals("NBA", byId.get(nba).sport());
        assertEquals("1895-96", byId.get(nba).seasonLabel());
        assertEquals("NFL", byId.get(nflGame).sport());
        assertEquals("1895", byId.get(nflGame).seasonLabel());
        // review S1: both games are still DRAFTING, so finish is null, not 0 (wasNull() once read the wrong column)
        assertEquals("DRAFTING", byId.get(nba).status());
        assertNull(byId.get(nba).finish(), "NBA, unfinished");
        assertNull(byId.get(nflGame).finish(), "NFL, unfinished");

        // and over HTTP, the way the web page reads it
        String body = mvc.perform(get("/api/history/games").header("X-Sleeper-User", USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body);
        assertEquals(2, rows.size());
        List<String> sports = new ArrayList<>();
        rows.forEach(r -> sports.add(r.get("sport").asText()));
        assertTrue(sports.containsAll(List.of("NBA", "NFL")));
        rows.forEach(r -> assertTrue(r.get("finish").isNull(), "finish is null over HTTP until COMPLETE: " + r));

        // each game still reads its own sport's season
        assertEquals("1895-96", json.valueToTree(service.get(nba, USER).orElseThrow()).get("season").get("label").asText());
        assertEquals("1895", json.valueToTree(service.get(nflGame, USER).orElseThrow()).get("season").get("label").asText());
    }

    @Test
    void anNbaSeasonUpsertStillSucceedsAndLeavesTheFootballRowAlone() {
        SeasonRow nba = data.findSeason("NBA", 1895).orElseThrow();
        SeasonRow changed = new SeasonRow("NBA", 1895, nba.label(), nba.source(), nba.openingNight(),
                nba.lastCountedDate(), nba.weekCount(), 777, nba.countedStats(), nba.excludedStatsJson(),
                nba.statCoverageJson(), nba.rankingSource(), OffsetDateTime.now(), null, 0, null);
        data.upsertSeason(changed);   // the on-conflict (sport, season_start) re-run
        data.upsertSeason(changed);
        assertEquals(777, data.findSeason("NBA", 1895).orElseThrow().gameCount());
        SeasonRow football = data.findSeason("NFL", 1895).orElseThrow();
        assertEquals(60, football.gameCount(), "the NFL row at the same number is untouched");
        assertEquals("NFLVERSE", football.source());
        assertEquals(2, jdbc.queryForObject("select count(*) from historical_season where season_start = 1895",
                Integer.class));
    }

    @Test
    void theOfferedSeasonsOfOneSportNeverContainTheOthers() {
        List<SeasonRow> nba = data.offeredSeasons("NBA", 1893, 1895);
        List<SeasonRow> nflRows = data.offeredSeasons("NFL", 1893, 1895);
        assertEquals(List.of(1895), nba.stream().map(SeasonRow::seasonStart).toList());
        assertEquals(List.of(1894, 1895), nflRows.stream().map(SeasonRow::seasonStart).toList());
        assertTrue(nba.stream().allMatch(s -> s.sport().equals("NBA") && s.source().equals("KAGGLE")));
        assertTrue(nflRows.stream().allMatch(s -> s.sport().equals("NFL") && s.source().equals("NFLVERSE")));

        // Take away the basketball prior-season lines: NBA 1895 stops being offered, football's is unaffected, because
        // each sport's "prior lines exist" check reads its own table.
        jdbc.update("delete from historical_player_season where season_start = 1894");
        assertTrue(data.offeredSeasons("NBA", 1893, 1895).isEmpty());
        assertEquals(List.of(1894, 1895), data.offeredSeasons("NFL", 1893, 1895).stream().map(SeasonRow::seasonStart).toList());
        // ... and the other way round
        reseed(jdbc, data, nfl);
        jdbc.update("delete from historical_nfl_player_season where season = 1894");
        assertEquals(List.of(1895), data.offeredSeasons("NBA", 1893, 1895).stream().map(SeasonRow::seasonStart).toList());
        assertTrue(data.offeredSeasons("NFL", 1893, 1895).stream().noneMatch(s -> s.seasonStart() == 1895));
    }

    // ---- F9: a game written before this feature still reopens ----------------------------------------------

    @Test
    void aLiteral053ShapeBoardReopensWithEmptyNotesNullAttributionAndTheOldDisclosure() {
        long id = games.create(USER, "NBA", HistoricalSyntheticSeason.S, 12, 14, 0, LeagueShape.NBA_ROSTER,
                "{\"pts\":1.0}", 4, 99L, BOARD_053, OffsetDateTime.now());
        HistoricalGameState s = service.get(id, USER).orElseThrow();
        assertEquals("NBA", s.sport());
        assertEquals(List.of(), s.season().dataNotes());
        assertNull(s.season().attribution());
        assertEquals(2, s.available().size());
        HistoricalPlayerCard old = s.available().get(0);
        assertNull(old.rookiePick());
        assertEquals(java.util.Map.of(), old.prior().totals(), "a card with no totals key reads as empty, never null");
        assertEquals(10.5, old.prior().perGame().get("pts"));
        assertNull(s.available().get(1).prior());
        assertEquals("not tracked by the league that season", s.season().excludedStats().get("stl"),
                "the 053 snapshot's own era disclosure is what is shown");
        assertEquals(NbaHistoricalSport.SETTINGS_SOURCE, s.season().settingsSource());
        // and the wire form has the keys the web type requires
        JsonNode wire = json.valueToTree(s);
        assertTrue(wire.get("season").get("dataNotes").isArray());
        assertTrue(wire.get("season").get("attribution").isNull());
    }

    @Test
    void aFinishedNbaGameRewrittenTo053ShapeReopensWithAnIdenticalResult() throws Exception {
        long id = service.create(USER, "NBA", 9, HistoricalSyntheticSeason.S).id();
        var done = service.auto(id, HistoricalGameService.Scope.FINISH, USER).orElseThrow();
        assertEquals("COMPLETE", done.status());
        String storedResult = jdbc.queryForObject("select result::text from historical_game where id = ?", String.class, id);

        // Rewrite the stored board exactly as 053 wrote it: strip the keys this feature added.
        ObjectNode board = (ObjectNode) json.readTree(jdbc.queryForObject(
                "select board::text from historical_game where id = ?", String.class, id));
        board.remove("dataNotes");
        board.remove("attribution");
        for (JsonNode card : board.get("cards")) {
            ((ObjectNode) card).remove("rookiePick");
            JsonNode prior = card.get("prior");
            if (prior != null && prior.isObject()) ((ObjectNode) prior).remove("totals");
        }
        assertFalse(board.toString().contains("dataNotes"));
        assertFalse(board.toString().contains("rookiePick"));
        jdbc.update("update historical_game set board = ?::jsonb where id = ?", board.toString(), id);

        HistoricalGameState reopened = service.get(id, USER).orElseThrow();
        assertEquals("COMPLETE", reopened.status());
        assertEquals(json.readTree(storedResult), reopened.result(), "the frozen result is served unchanged");
        assertEquals(List.of(), reopened.season().dataNotes());
        assertNull(reopened.season().attribution());
        assertEquals(168, reopened.picks().size());
        assertTrue(reopened.picks().stream().allMatch(p -> p.player() != null), "every pick still resolves to a card");
    }
}
