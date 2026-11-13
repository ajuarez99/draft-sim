package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import com.ballknowers.draftsim.historical.NflHistoricalRepository;
import com.ballknowers.draftsim.historical.NflSyntheticSeason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

@SpringBootTest
@AutoConfigureMockMvc
class HistoricalAchievementWireIT {
    private static final String U = "it-hwire-alice";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired HistoricalDataRepository data;
    @Autowired NflHistoricalRepository nfl;
    @Autowired HistoricalGameService games;

    @BeforeEach @AfterEach
    void clean() {
        jdbc.update("delete from historical_achievement where sleeper_user_id like 'it-hwire-%'");
        jdbc.update("delete from historical_profile where sleeper_user_id like 'it-hwire-%'");
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-hwire-%'");
        NflSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.clean(jdbc);
    }

    JsonNode getJson(String path) throws Exception {
        MvcResult r = mvc.perform(get(path).header("X-Sleeper-User", U)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), path + " -> " + r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString());
    }

    void run(String sport, int season, int slots) throws Exception {
        Map<String, Integer> momentCodes = new TreeMap<>();
        long[] ids = new long[slots];
        for (int s = 1; s <= slots; s++) {
            long id = games.create(U, sport, s, season).id();
            assertEquals("COMPLETE", games.auto(id, HistoricalGameService.Scope.FINISH, U).orElseThrow().status());
            ids[s - 1] = id;
        }
        String firstAwardRaw = null;
        for (long id : ids) {
            JsonNode aw = getJson("/api/history/games/" + id + "/award");
            assertTrue(aw.get("settled").asBoolean());
            for (String k : new String[]{"gameId", "settled", "fromSavedResult", "xp", "lines", "badges", "moments",
                    "momentsSeen", "total"}) {
                assertTrue(aw.has(k), "award wire field " + k);
            }
            int sum = 0;
            for (JsonNode l : aw.get("lines")) sum += l.get("xp").asInt();
            assertEquals(aw.get("xp").asInt(), sum);
            for (JsonNode m : aw.get("moments")) momentCodes.merge(m.get("code").asText(), 1, Integer::sum);
            if (aw.get("moments").size() > 0 && firstAwardRaw == null) firstAwardRaw = aw.toString();

            // stored bust vs captured facts
            String res = jdbc.queryForObject("select result::text from historical_game where id = ?", String.class, id);
            String pf = jdbc.queryForObject("select pick_facts::text from historical_game where id = ?", String.class, id);
            assertNotNull(pf, sport + " pick facts captured");
            JsonNode r = json.readTree(res), f = json.readTree(pf);
            JsonNode bust = r.get("biggestBust");
            int maxGap = 0, maxPick = -1, maxRank = -1;
            for (JsonNode p : f.get("picks")) {
                int gap = p.get("seasonValueRank").asInt() - p.get("pickNo").asInt();
                if (gap > maxGap) { maxGap = gap; maxPick = p.get("pickNo").asInt(); maxRank = p.get("seasonValueRank").asInt(); }
            }
            if (bust == null || bust.isNull()) assertEquals(0, maxGap, "no stored bust but facts show one");
            else {
                assertEquals(bust.get("pickNo").asInt(), maxPick, "bust pick agrees");
                assertEquals(bust.get("seasonValueRank").asInt(), maxRank, "bust rank agrees");
            }
            int wins = 0;
            int userSlot = jdbc.queryForObject("select user_slot from historical_game where id = ?", Integer.class, id);
            for (JsonNode st : r.get("standings")) if (st.get("slot").asInt() == userSlot) wins = st.get("wins").asInt();
            int pw = 0;
            if (r.hasNonNull("playoffs")) for (JsonNode rd : r.get("playoffs").get("rounds")) for (JsonNode m : rd.get("matchups"))
                if (m.get("winnerSlot").asInt() == userSlot) pw++;
            Map<String, Object> row = jdbc.queryForMap("select weekly_wins, playoff_wins, title, xp from historical_game_award where game_id = ?", id);
            assertEquals(wins + pw, row.get("weekly_wins"));
            assertEquals(pw, row.get("playoff_wins"));
            assertEquals("CHAMPION".equals(r.path("userOutcome").asText()), row.get("title"));
        }
        JsonNode p = getJson("/api/history/profile");
        long exp = jdbc.queryForObject("select coalesce(sum(xp),0) from historical_game_award where sleeper_user_id = ?", Long.class, U)
                + jdbc.queryForObject("select coalesce(sum(xp),0) from historical_achievement where sleeper_user_id = ?", Long.class, U);
        assertEquals(exp, p.get("xp").asLong());
        for (JsonNode b : p.get("badges")) {
            if (b.get("unlocked").asBoolean()) {
                java.time.OffsetDateTime.parse(b.get("unlockedAt").asText());
                assertTrue(b.get("detail").isObject(), "an earned badge carries its detail object");
            }
        }
    }

    @Test
    void nbaLong() throws Exception {
        HistoricalSyntheticSeason.seedLong(data);
        run("NBA", HistoricalSyntheticSeason.S, 12);
    }

    @Test
    void nflLong() throws Exception {
        NflSyntheticSeason.seedLong(nfl, jdbc);
        run("NFL", NflSyntheticSeason.S, 12);
    }

    @Test
    void corsPreflight() throws Exception {
        for (String path : new String[]{"/api/history/profile/seen", "/api/history/profile", "/api/history/games/1/award"}) {
            String method = path.endsWith("seen") ? "POST" : "GET";
            MvcResult ok = mvc.perform(options(path).header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", method)
                    .header("Access-Control-Request-Headers", "content-type,x-sleeper-user")).andReturn();
            assertEquals(200, ok.getResponse().getStatus());
            assertEquals("http://localhost:5173", ok.getResponse().getHeader("Access-Control-Allow-Origin"));
        }
        MvcResult bad = mvc.perform(options("/api/history/profile/seen").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-sleeper-user")).andReturn();
    }
}
