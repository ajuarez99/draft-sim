package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.HistoricalProperties;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSports;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import com.ballknowers.draftsim.historical.NbaHistoricalSport;
import com.ballknowers.draftsim.historical.SeasonReplay;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 053 T018 / T025: the historical game's HTTP surface over a made-up season ({@link HistoricalSyntheticSeason},
 * 1893..1895) so values can be seeded to be distinct and traced. Identity (401), ownership (404), turn and
 * taken-player conflicts (409), the FR-003 no-drafted-season-data rule while DRAFTING, the stored reversal round
 * (F8), and that a reopened COMPLETE game returns its frozen result.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricalGameMvcIT {

    private static final String ALICE = "it-hgame-alice", BOB = "it-hgame-bob";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping historical game IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameRepository gameRepo;
    @Autowired private HistoricalGameService service;
    @Autowired private HistoricalProperties props;
    @Autowired private DraftContextFactory contexts;
    @Autowired private MockDraftEngine engine;
    @Autowired private SportRulesRegistry rules;
    @Autowired private SeasonReplay replay;

    private static boolean seeded;

    private void ensureSeeded() {
        if (seeded) return;
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
        seeded = true;
    }

    @AfterAll
    static void removeTheMadeUpSeason() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        try {
            HistoricalSyntheticSeason.clean(new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "draftsim", "draftsim")));
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was seeded either.
        }
    }

    private JsonNode state(String user, String path, boolean post, String body, int expect) throws Exception {
        var req = post ? post(path) : get(path);
        req.header("X-Sleeper-User", user);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expect)).andReturn();
        String text = r.getResponse().getContentAsString();
        return text.isBlank() ? null : json.readTree(text);
    }

    private long newGame(String user, int slot) {
        ensureSeeded();
        return service.create(user, "NBA", slot, HistoricalSyntheticSeason.S).id();
    }

    private void cleanup() {
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-hgame-%'");
    }

    @Test
    void everyUserRouteWithoutAnIdentityHeaderIs401() throws Exception {
        for (String path : new String[]{"/api/history/games", "/api/history/games/1"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : new String[]{"/api/history/games", "/api/history/games/1/pick",
                "/api/history/games/1/auto", "/api/history/games/1/abandon"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    /** A real browser preflights every POST that sends X-Sleeper-User (bug class 6: curl never does). */
    @Test
    void aBrowserPreflightPassesForEveryGameRoute() throws Exception {
        for (String path : new String[]{"/api/history/games", "/api/history/games/1/pick",
                "/api/history/games/1/auto", "/api/history/games/1/abandon"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(path)
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type,x-sleeper-user"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }

    @Test
    void aDraftingStateHoldsNothingFromTheDraftedSeason() throws Exception {
        try {
            long id = newGame(ALICE, 5);
            JsonNode s = state(ALICE, "/api/history/games/" + id, false, null, 200);
            assertEquals("DRAFTING", s.get("status").asText());
            assertEquals(5, s.get("userSlot").asInt());
            assertEquals(5, s.get("currentPickNo").asInt(), "bots made picks 1..4, then it is the user's turn");
            assertTrue(s.get("isUsersTurn").asBoolean());
            assertEquals(4, s.get("picks").size());
            assertTrue(s.get("result").isNull());
            assertEquals(HistoricalSyntheticSeason.PLAYERS - 4, s.get("available").size());
            assertEquals("ranked by last season's points", s.get("season").get("rankingLabel").asText());

            // The drafted season's own line is: team ZZZ, age 99, 91 games, 7777 pts, 8888 reb, 9999 ast.
            // Its games are also stamped ZZZ. None of it may appear anywhere in what a drafter sees.
            String text = s.toString();
            for (String leak : new String[]{"ZZZ", "\"age\":99", "\"games\":91", "7777", "8888", "9999"}) {
                assertFalse(text.contains(leak), "drafted-season value leaked into the DRAFTING state: " + leak);
            }
            // ...and the prior season IS there: a card for the best player shows the 1894-95 line.
            JsonNode top = s.get("available").get(0);
            assertEquals("1894-95", top.get("prior").get("seasonLabel").asText());
            assertTrue(top.get("team").asText().startsWith("T"), "team is the prior season's");
            // the board is ranked ascending, rank 1 first
            int prev = 0;
            for (JsonNode c : s.get("available")) {
                assertTrue(c.get("rank").asInt() > prev);
                prev = c.get("rank").asInt();
            }
        } finally {
            cleanup();
        }
    }

    @Test
    void anotherUsersGameIs404OnEveryRoute() throws Exception {
        try {
            long id = newGame(ALICE, 3);
            mvc.perform(get("/api/history/games/" + id).header("X-Sleeper-User", BOB)).andExpect(status().isNotFound());
            mvc.perform(post("/api/history/games/" + id + "/pick").header("X-Sleeper-User", BOB)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":1}")).andExpect(status().isNotFound());
            mvc.perform(post("/api/history/games/" + id + "/auto").header("X-Sleeper-User", BOB)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"PICK\"}")).andExpect(status().isNotFound());
            mvc.perform(post("/api/history/games/" + id + "/abandon").header("X-Sleeper-User", BOB))
                    .andExpect(status().isNotFound());
            // and it is not in Bob's list
            assertEquals(0, state(BOB, "/api/history/games", false, null, 200).size());
            assertEquals(1, state(ALICE, "/api/history/games", false, null, 200).size());
        } finally {
            cleanup();
        }
    }

    @Test
    void conflictsAre409AndABadPlayerIs400() throws Exception {
        try {
            long id = newGame(ALICE, 5);
            JsonNode s = state(ALICE, "/api/history/games/" + id, false, null, 200);

            // a player a bot already took
            long taken = s.get("picks").get(0).get("player").get("id").asLong();
            state(ALICE, "/api/history/games/" + id + "/pick", true, "{\"playerId\":" + taken + "}", 409);
            // a player who is not on this board
            state(ALICE, "/api/history/games/" + id + "/pick", true, "{\"playerId\":999999999}", 400);
            state(ALICE, "/api/history/games/" + id + "/pick", true, "{}", 400);

            // out of turn: wind the cursor to pick 1, which belongs to slot 1, not the user's slot 5
            jdbc.update("update historical_game set current_pick_no = 1 where id = ?", id);
            long free = s.get("available").get(0).get("id").asLong();
            state(ALICE, "/api/history/games/" + id + "/pick", true, "{\"playerId\":" + free + "}", 409);
            state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"PICK\"}", 409);
            jdbc.update("update historical_game set current_pick_no = 5 where id = ?", id);

            // a legal pick works and advances the bots to the user's next turn, pick 20 (round 2, reversed: slot 5 -> 8th)
            JsonNode after = state(ALICE, "/api/history/games/" + id + "/pick", true,
                    "{\"playerId\":" + free + "}", 200);
            assertEquals(5, after.get("picks").get(4).get("pickNo").asInt());
            assertEquals("USER", after.get("picks").get(4).get("seatType").asText());
            assertEquals(DraftSlot.picksForSlot(5, 12, 14, 0)[1], after.get("currentPickNo").asInt());
            assertTrue(after.get("isUsersTurn").asBoolean());

            // abandoning ends it: every further move is a 409
            mvc.perform(post("/api/history/games/" + id + "/abandon").header("X-Sleeper-User", ALICE))
                    .andExpect(status().isNoContent());
            assertEquals("ABANDONED", state(ALICE, "/api/history/games/" + id, false, null, 200).get("status").asText());
            state(ALICE, "/api/history/games/" + id + "/pick", true, "{\"playerId\":" + s.get("available").get(1).get("id").asLong() + "}", 409);
            state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"FINISH\"}", 409);
            mvc.perform(post("/api/history/games/" + id + "/abandon").header("X-Sleeper-User", ALICE))
                    .andExpect(status().isConflict());
        } finally {
            cleanup();
        }
    }

    @Test
    void theDisclosureIsFrozenAtCreationAndStatsComeBackInTheCanonicalOrder() throws Exception {
        try {
            long id = newGame(ALICE, 5);
            // a re-import moves a stat across the era threshold after the game was created
            jdbc.update("update historical_season set excluded_stats = '{\"pts\":\"changed later\"}'::jsonb,"
                    + " stat_coverage = '{\"pts\":0.1}'::jsonb where season_start = ?", HistoricalSyntheticSeason.S);
            JsonNode season = state(ALICE, "/api/history/games/" + id, false, null, 200).get("season");
            assertTrue(season.get("excludedStats").has("stl"), "the frozen disclosure, not the live season row");
            assertFalse(season.get("excludedStats").has("pts"));
            assertEquals(1.0, season.get("statCoverage").get("pts").asDouble(), 1e-9);
            // pts, reb, ast: never the jsonb / hash order
            assertEquals("[\"pts\",\"reb\",\"ast\"]", season.get("countedStats").toString());
            assertEquals(java.util.List.of("pts", "reb", "ast"), iterate(season.get("statCoverage").fieldNames()));
            JsonNode perGame = state(ALICE, "/api/history/games/" + id, false, null, 200)
                    .get("available").get(0).get("prior").get("perGame");
            assertEquals(java.util.List.of("pts", "reb", "ast"), iterate(perGame.fieldNames()));
        } finally {
            jdbc.update("update historical_season set excluded_stats = '{\"stl\":\"not tracked by the league that season\"}'::jsonb,"
                    + " stat_coverage = '{\"pts\":1.0,\"reb\":0.97,\"ast\":0.95}'::jsonb where season_start = ?",
                    HistoricalSyntheticSeason.S);
            cleanup();
        }
    }

    private static java.util.List<String> iterate(java.util.Iterator<String> it) {
        java.util.List<String> out = new java.util.ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    @Test
    void aDraftedPlayerWhoLostAllGameRowsToAReImportFailsLoudAtTheLastPickInsteadOfScoringZero() throws Exception {
        try {
            long id = newGame(ALICE, 9);
            jdbc.update("delete from historical_player_game where season_start = ?", HistoricalSyntheticSeason.S);
            MvcResult r = mvc.perform(post("/api/history/games/" + id + "/auto").header("X-Sleeper-User", ALICE)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"FINISH\"}"))
                    .andExpect(status().isConflict()).andReturn();
            assertTrue(r.getResponse().getContentAsString().contains("re-imported"),
                    "the 409 says why: " + r.getResponse().getContentAsString());
            assertEquals("DRAFTING", state(ALICE, "/api/history/games/" + id, false, null, 200).get("status").asText());
        } finally {
            cleanup();
            HistoricalSyntheticSeason.clean(jdbc);
            HistoricalSyntheticSeason.seed(data);
        }
    }

    @Test
    void autoFinishCompletesAndReopeningReturnsTheFrozenResult() throws Exception {
        try {
            long id = newGame(ALICE, 9);
            JsonNode one = state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"PICK\"}", 200);
            assertEquals("DRAFTING", one.get("status").asText());
            assertEquals(16, one.get("currentPickNo").asInt(),
                    "slot 9 picks 9, then the bots run to its next turn at pick 16");

            JsonNode done = state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"FINISH\"}", 200);
            assertEquals("COMPLETE", done.get("status").asText());
            assertEquals(168, done.get("picks").size());
            assertEquals(0, done.get("available").size());
            JsonNode result = done.get("result");
            assertEquals(12, result.get("standings").size());
            assertEquals(HistoricalSyntheticSeason.WEEKS, result.get("weeks").size());
            assertEquals(6, result.get("weeks").get(0).get("matchups").size());
            assertEquals(14, state(ALICE, "/api/history/games/" + id, false, null, 200).get("rounds").asInt());
            assertEquals("greedy hindsight, bots unchanged", result.get("hindsight").get("label").asText());
            assertEquals(14, result.get("hindsight").get("picks").size());
            assertTrue(result.get("lineups").has("9"));
            Set<Integer> places = new HashSet<>();
            for (JsonNode st : result.get("standings")) places.add(st.get("place").asInt());
            assertEquals(12, places.size());
            int userFinish = result.get("userFinish").asInt();
            assertTrue(userFinish >= 1 && userFinish <= 12);

            // a finished game rejects further moves
            state(ALICE, "/api/history/games/" + id + "/pick", true, "{\"playerId\":1}", 409);
            state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"FINISH\"}", 409);

            // FR-013: reopening returns exactly the stored result, whatever the config says now
            String stored = jdbc.queryForObject("select result::text from historical_game where id = ?", String.class, id);
            JsonNode reopened = state(ALICE, "/api/history/games/" + id, false, null, 200).get("result");
            assertEquals(json.readTree(stored), reopened);
            assertEquals(userFinish, reopened.get("userFinish").asInt());

            // FR-013, harder: read through a service whose scoring config is completely different. If the read
            // path re-scored anything, the points would move. Compared as raw text from the database.
            java.util.Map<String, Double> other = new java.util.LinkedHashMap<>();
            for (var e : props.scoring().entrySet()) other.put(e.getKey(), e.getValue() * 10 + 1);
            HistoricalProperties changed = new HistoricalProperties(props.kaggleZipUrl(), props.kaggleZipSha256(),
                    props.eraThreshold(), props.rankStandardGames(), props.rankMinGames(), props.rankLookbackSeasons(),
                    props.teams(), props.rounds(), props.reversalRound(), other, props.regularSeasonWeeks());
            HistoricalGameService svc = new HistoricalGameService(data, gameRepo, contexts, engine, rules, replay,
                    new HistoricalSports(java.util.List.of(new NbaHistoricalSport(changed, data))), json);
            JsonNode viaChanged = json.valueToTree(svc.get(id, ALICE).orElseThrow().result());
            assertEquals(json.readTree(stored), viaChanged, "reopen under changed scoring config must not re-score");

            // ...and the read path returns the stored column, not a recomputation: tamper with the stored value
            // and the reopened game shows the tampered value.
            jdbc.update("update historical_game set result = jsonb_set(result, '{userFinish}', '99') where id = ?", id);
            assertEquals(99, state(ALICE, "/api/history/games/" + id, false, null, 200)
                    .get("result").get("userFinish").asInt(), "the read path serves the stored result as-is");
            jdbc.update("update historical_game set result = ?::jsonb where id = ?", stored, id);

            // the list carries the finish
            JsonNode list = state(ALICE, "/api/history/games", false, null, 200);
            assertEquals(userFinish, list.get(0).get("finish").asInt());
            assertEquals("1895-96", list.get(0).get("seasonLabel").asText());
        } finally {
            cleanup();
        }
    }

    @Test
    void theStoredReversalRoundDrivesEveryTurnAndTheBots() throws Exception {
        // A service built with reversal round 3, over the same beans (plan F8).
        HistoricalProperties rev3 = new HistoricalProperties(props.kaggleZipUrl(), props.kaggleZipSha256(),
                props.eraThreshold(), props.rankStandardGames(), props.rankMinGames(), props.rankLookbackSeasons(), props.teams(), props.rounds(),
                3, props.scoring(), props.regularSeasonWeeks());
        HistoricalGameService svc = new HistoricalGameService(data, gameRepo, contexts, engine, rules, replay,
                new HistoricalSports(java.util.List.of(new NbaHistoricalSport(rev3, data))), json);
        try {
            ensureSeeded();
            long id = svc.create(ALICE, "NBA", 4, HistoricalSyntheticSeason.S).id();
            assertEquals(3, jdbc.queryForObject("select reversal_round from historical_game where id = ?",
                    Integer.class, id));
            assertEquals(3, svc.get(id, ALICE).orElseThrow().reversalRound(), "the state carries the stored value");
            var done = svc.auto(id, HistoricalGameService.Scope.FINISH, ALICE).orElseThrow();
            assertEquals("COMPLETE", done.status());
            // pick 25 is round 3: plain snake would run it forward (slot 1), reversal 3 flips it (slot 12)
            assertEquals(12, done.picks().get(24).draftSlot());
            assertEquals(1, done.picks().get(35).draftSlot(), "pick 36, the end of that flipped round");
            int[] mine = DraftSlot.picksForSlot(4, 12, 14, 3);
            assertEquals(java.util.Arrays.stream(mine).boxed().toList(), done.myPicks());
            for (int pickNo : mine) {
                assertEquals("USER", done.picks().get(pickNo - 1).seatType(), "pick " + pickNo + " is the user's");
                assertEquals(4, done.picks().get(pickNo - 1).draftSlot());
            }
        } finally {
            cleanup();
        }
    }


    // ---- spec 063: playoffs --------------------------------------------------------------------------

    @Test
    void aShortSeasonFinishesInTheNoPlayoffsArmAndTheListSaysSo() throws Exception {
        try {
            long id = newGame(ALICE, 9);
            JsonNode done = state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"FINISH\"}", 200);
            JsonNode result = done.get("result");
            assertEquals(2, result.get("format").get("version").asInt());
            assertEquals(0, result.get("format").get("playoffWeeks").size());
            assertTrue(result.get("playoffs").isNull());
            assertEquals("NO_PLAYOFFS", result.get("userOutcome").asText());
            assertEquals(result.get("userFinish").asInt(), result.get("finalPlaces").get(
                    result.get("userFinish").asInt() - 1).get("place").asInt());
            for (JsonNode w : result.get("weeks")) assertEquals("REGULAR", w.get("phase").asText());
            assertEquals(0, done.get("reversalRound").asInt(), "the game state carries its stored reversal round");
            JsonNode row = state(ALICE, "/api/history/games", false, null, 200).get(0);
            assertEquals(2, row.get("formatVersion").asInt());
            assertEquals("NO_PLAYOFFS", row.get("outcome").asText());
        } finally {
            cleanup();
        }
    }

    @Test
    void aLongSeasonEndsInAThreeRoundBracketAndTheListCarriesTheOutcome() throws Exception {
        try {
            ensureSeeded();
            HistoricalSyntheticSeason.clean(jdbc);
            HistoricalSyntheticSeason.seedLong(data);
            long id = newGame(ALICE, 9);
            JsonNode done = state(ALICE, "/api/history/games/" + id + "/auto", true, "{\"scope\":\"FINISH\"}", 200);
            JsonNode result = done.get("result");
            JsonNode fmt = result.get("format");
            assertEquals(2, fmt.get("version").asInt());
            assertEquals(props.regularSeasonWeeks(), fmt.get("regularSeasonWeeks").asInt());
            assertEquals(3, fmt.get("playoffWeeks").size());
            assertEquals(HistoricalSyntheticSeason.LONG_WEEKS - props.regularSeasonWeeks() - 3,
                    fmt.get("unusedWeeks").asInt());
            assertEquals(3, result.get("playoffs").get("rounds").size());
            assertEquals(6, result.get("playoffs").get("seeds").size());
            assertEquals(props.regularSeasonWeeks() + 3, result.get("weeks").size(),
                    "regular season, then three playoff weeks, nothing after");
            int playoffWeeks = 0;
            for (JsonNode w : result.get("weeks")) {
                if ("PLAYOFF".equals(w.get("phase").asText())) playoffWeeks++;
                else assertEquals(6, w.get("matchups").size());
            }
            assertEquals(3, playoffWeeks);
            assertEquals(12, result.get("finalPlaces").size());
            assertEquals(result.get("userFinish").asInt(), result.get("finalPlaces").get(
                    result.get("userFinish").asInt() - 1).get("place").asInt());
            int regular = props.regularSeasonWeeks();
            for (JsonNode st : result.get("standings")) {
                assertEquals(regular, st.get("wins").asInt() + st.get("losses").asInt() + st.get("ties").asInt(),
                        "standings are the regular season only");
            }
            String outcome = result.get("userOutcome").asText();
            JsonNode row = state(ALICE, "/api/history/games", false, null, 200).get(0);
            assertEquals(2, row.get("formatVersion").asInt());
            assertEquals(outcome, row.get("outcome").asText());
            assertEquals(result.get("userFinish").asInt(), row.get("finish").asInt());
        } finally {
            cleanup();
            HistoricalSyntheticSeason.clean(jdbc);
            HistoricalSyntheticSeason.seed(data);
        }
    }

    /** Plan A6 / PR-09: a result stored by spec 062 comes back as an equal JSON tree, unmarked by the new format. */
    @Test
    void aGameCompletedBeforePlayoffsComesBackAsItWasStoredAndTheListLeavesTheNewFieldsNull() throws Exception {
        try {
            long id = newGame(ALICE, 9);
            String old = new String(getClass().getResourceAsStream("/historical/result-062.json").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            JsonNode oldTree = json.readTree(old);
            assertFalse(oldTree.has("format"), "the fixture is 062-shaped");
            jdbc.update("update historical_game set status = 'COMPLETE', result = ?::jsonb where id = ?", old, id);

            JsonNode state = state(ALICE, "/api/history/games/" + id, false, null, 200);
            assertEquals(oldTree, state.get("result"), "an equal tree: jsonb does not keep bytes or key order");
            assertNull(state.get("result").get("format"));
            assertNull(state.get("result").get("userOutcome"));
            JsonNode row = state(ALICE, "/api/history/games", false, null, 200).get(0);
            assertEquals(oldTree.get("userFinish").asInt(), row.get("finish").asInt());
            assertTrue(row.get("formatVersion").isNull(), "an old row has no format version");
            assertTrue(row.get("outcome").isNull(), "and no outcome: its finish is a standings rank");
        } finally {
            cleanup();
        }
    }

    /** Spec 056 T021: sport is required on create, with no default. None of these reach a season lookup. */
    @Test
    void createWithoutOrWithAnUnknownSportIs400() throws Exception {
        for (String body : new String[]{null, "{}", "{\"userSlot\":3}", "{\"sport\":null,\"userSlot\":3}",
                "{\"sport\":\"\"}", "{\"sport\":\"MLB\"}", "{\"sport\":\"nba\"}"}) {
            var req = post("/api/history/games").header("X-Sleeper-User", ALICE);
            if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
            mvc.perform(req).andExpect(status().isBadRequest());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from historical_game where sleeper_user_id like 'it-hgame-%'",
                Integer.class), "a refused create writes nothing");
    }

    @Test
    void theListNamesTheSportOfEachGame() throws Exception {
        try {
            newGame(ALICE, 2);
            JsonNode list = state(ALICE, "/api/history/games", false, null, 200);
            assertEquals("NBA", list.get(0).get("sport").asText());
        } finally {
            cleanup();
        }
    }

    @Test
    void postCreatesARealSeasonGameAs201WithTheBotsAdvanced() throws Exception {
        try {
            Assumptions.assumeTrue(!data.offeredSeasons("NBA").isEmpty(), "no real seasons imported -- skipping");
            MvcResult r = mvc.perform(post("/api/history/games").header("X-Sleeper-User", ALICE)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"sport\":\"NBA\",\"userSlot\":7}"))
                    .andExpect(status().isCreated()).andReturn();
            JsonNode s = json.readTree(r.getResponse().getContentAsString());
            assertEquals("DRAFTING", s.get("status").asText());
            assertEquals(7, s.get("userSlot").asInt());
            assertEquals(7, s.get("currentPickNo").asInt());
            assertTrue(s.get("isUsersTurn").asBoolean());
            // out-of-range slot is a 400
            mvc.perform(post("/api/history/games").header("X-Sleeper-User", ALICE)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"sport\":\"NBA\",\"userSlot\":13}"))
                    .andExpect(status().isBadRequest());
        } finally {
            cleanup();
        }
    }
}
