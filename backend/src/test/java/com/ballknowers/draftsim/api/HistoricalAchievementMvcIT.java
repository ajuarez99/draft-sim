package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalAchievementService;
import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 064: Time Machine achievements against Postgres, over the made-up season. Lazy settle (idempotent and safe
 * under concurrent calls, one bad game does not stop the others), the quiet backfill of games that predate
 * achievements, the mixed first batch (amendment A9), ownership (404 before 409), identity (401) and the
 * monotonic "seen" marks.
 *
 * <p>Cleans {@code historical_achievement}, {@code historical_profile} and the awards itself: only the awards
 * cascade from a deleted game.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricalAchievementMvcIT {

    private static final String ALICE = "it-hach-alice", BOB = "it-hach-bob";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping achievement IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameService games;
    @Autowired private HistoricalAchievementService achievements;

    @BeforeEach
    void freshWorld() {
        cleanUsers();
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
    }

    @AfterAll
    static void removeEverything() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "draftsim", "draftsim"));
            j.update("delete from historical_achievement where sleeper_user_id like 'it-hach-%'");
            j.update("delete from historical_profile where sleeper_user_id like 'it-hach-%'");
            j.update("delete from historical_game where sleeper_user_id like 'it-hach-%'");
            HistoricalSyntheticSeason.clean(j);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was seeded either.
        }
    }

    private void cleanUsers() {
        jdbc.update("delete from historical_achievement where sleeper_user_id like 'it-hach-%'");
        jdbc.update("delete from historical_profile where sleeper_user_id like 'it-hach-%'");
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-hach-%'");   // awards cascade
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private long finishedGame(String user, int slot) {
        long id = games.create(user, "NBA", slot, HistoricalSyntheticSeason.S).id();
        var s = games.auto(id, HistoricalGameService.Scope.FINISH, user).orElseThrow();
        assertEquals("COMPLETE", s.status());
        return id;
    }

    /** A game completed before achievements existed: the 062-shaped result, flagged pre_achievements. */
    private long oldGame(String user, int daysAgo) throws Exception {
        long id = games.create(user, "NBA", 5, HistoricalSyntheticSeason.S).id();
        String old = new String(getClass().getResourceAsStream("/historical/result-062.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        jdbc.update("""
                update historical_game set status = 'COMPLETE', result = ?::jsonb, pre_achievements = true,
                    completed_at = now() - make_interval(days => ?) where id = ?
                """, old, daysAgo, id);
        return id;
    }

    private JsonNode body(MvcResult r) throws Exception {
        String t = r.getResponse().getContentAsString();
        return t.isBlank() ? null : json.readTree(t);
    }

    private JsonNode profile(String user) throws Exception {
        return body(mvc.perform(get("/api/history/profile").header("X-Sleeper-User", user))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode award(String user, long gameId) throws Exception {
        return body(mvc.perform(get("/api/history/games/" + gameId + "/award").header("X-Sleeper-User", user))
                .andExpect(status().isOk()).andReturn());
    }

    private void seen(String user, String bodyJson) throws Exception {
        mvc.perform(post("/api/history/profile/seen").header("X-Sleeper-User", user)
                .contentType(MediaType.APPLICATION_JSON).content(bodyJson)).andExpect(status().isNoContent());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- SC-003: settle is idempotent and safe under concurrency ---------------------------------------

    @Test
    void concurrentSettlesNeverDoubleCountAGame() throws Exception {
        long a = finishedGame(ALICE, 3);
        long b = finishedGame(ALICE, 8);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return achievements.settle(ALICE);
                }));
            }
            go.countDown();
            for (Future<Integer> f : futures) assertEquals(0, f.get(), "no failures");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, count("select count(*) from historical_game_award where sleeper_user_id = ?", ALICE));
        assertEquals(1, count("select count(*) from historical_achievement where sleeper_user_id = ? and code = 'FIRST_GAME'",
                ALICE));
        assertEquals(a, jdbc.queryForObject("select game_id from historical_achievement where sleeper_user_id = ?"
                + " and code = 'FIRST_GAME'", Long.class, ALICE), "the older game unlocked it");

        JsonNode p = profile(ALICE);
        long expected = jdbc.queryForObject("select coalesce(sum(xp),0) from historical_game_award where"
                + " sleeper_user_id = ?", Long.class, ALICE)
                + jdbc.queryForObject("select coalesce(sum(xp),0) from historical_achievement where"
                + " sleeper_user_id = ?", Long.class, ALICE);
        assertEquals(expected, p.get("xp").asLong(), "profile xp = every game's xp + every earned badge's xp");
        assertEquals(2, p.get("totals").get("games").asInt());
        assertEquals(0, p.get("settleFailures").asInt());
        assertEquals(p.get("xp").asLong(), profile(ALICE).get("xp").asLong(), "reading twice changes nothing");

        // a game's lines sum to its xp, and total = xp + the badges it unlocked
        for (long id : new long[]{a, b}) {
            JsonNode aw = award(ALICE, id);
            assertTrue(aw.get("settled").asBoolean());
            int sum = 0;
            for (JsonNode l : aw.get("lines")) sum += l.get("xp").asInt();
            assertEquals(aw.get("xp").asInt(), sum);
            int badgeXp = 0;
            for (JsonNode bd : aw.get("badges")) badgeXp += bd.get("xp").asInt();
            assertEquals(aw.get("xp").asInt() + badgeXp, aw.get("total").asInt());
        }
        assertEquals(14, jdbc.queryForObject("select jsonb_array_length(pick_facts -> 'picks') from historical_game"
                + " where id = ?", Integer.class, a), "pick facts were captured at completion");
    }

    // ---- FR-005 / SC-004: quiet backfill ---------------------------------------------------------------

    @Test
    void aGameFromBeforeAchievementsSettlesQuietlyFromItsSavedResult() throws Exception {
        long old = oldGame(ALICE, 3);
        JsonNode p = profile(ALICE);
        // CR-04: a badge is dated when the game finished (three days ago), not when it was settled
        assertEquals(2, count("select count(*) from historical_achievement where game_id = ?"
                + " and unlocked_at = (select completed_at from historical_game where id = ?)", old, old));
        // 100 game + 7 wins * 10, plus FIRST_GAME 50 and WINS_1 50
        assertEquals(270, p.get("xp").asInt());
        assertEquals(2, p.get("level").asInt());
        assertEquals(2, p.get("seenLevel").asInt(), "no level-up for a backfilled game");
        assertEquals(1, p.get("totals").get("gamesFromSavedResults").asInt());
        int unlocked = 0;
        for (JsonNode b : p.get("badges")) {
            if (b.get("unlocked").asBoolean()) {
                unlocked++;
                assertTrue(b.get("seen").asBoolean(), b.get("code").asText() + " must be quiet");
            }
        }
        assertEquals(2, unlocked);
        JsonNode aw = award(ALICE, old);
        assertTrue(aw.get("fromSavedResult").asBoolean());
        assertTrue(aw.get("momentsSeen").asBoolean());
        assertEquals(0, aw.get("moments").size());
    }

    @Test
    void aMixedFirstBatchKeepsTheOldGamesLevelQuietButLeavesTheNewGameLoud() throws Exception {
        long old = oldGame(ALICE, 3);
        long fresh = finishedGame(ALICE, 4);
        JsonNode p = profile(ALICE);
        assertEquals(2, p.get("seenLevel").asInt(), "raised to the level after the old game only");
        assertTrue(p.get("level").asInt() >= p.get("seenLevel").asInt());
        assertEquals(0, count("select count(*) from historical_achievement where game_id = ? and seen_at is null", old));
        assertEquals(count("select count(*) from historical_achievement where game_id = ?", fresh),
                count("select count(*) from historical_achievement where game_id = ? and seen_at is null", fresh),
                "the new game's badges are unseen");
        assertFalse(award(ALICE, fresh).get("fromSavedResult").asBoolean());
        assertFalse(award(ALICE, fresh).get("momentsSeen").asBoolean());
        assertTrue(award(ALICE, old).get("fromSavedResult").asBoolean());
    }

    @Test
    void aNewGameSettledLateStillDatesItsBadgesFromWhenItWasFinished() throws Exception {
        long id = finishedGame(ALICE, 3);
        jdbc.update("update historical_game set completed_at = now() - interval '5 days' where id = ?", id);
        profile(ALICE);   // settles now, five days after completion
        int badges = count("select count(*) from historical_achievement where game_id = ?", id);
        assertTrue(badges >= 1);
        assertEquals(badges, count("select count(*) from historical_achievement where game_id = ?"
                + " and unlocked_at = (select completed_at from historical_game where id = ?)", id, id),
                "every badge of the game, cumulative ones included, carries the game's completed_at");
        assertEquals(badges, count("select count(*) from historical_achievement where game_id = ?"
                + " and seen_at is null", id), "seen_at is untouched: the new game's badges are still unseen");
    }

    // ---- FR-004 / A3: one bad game does not stop the others --------------------------------------------

    @Test
    void aGameThatCannotSettleIsCountedAndTheOthersStillSettle() throws Exception {
        long bad = finishedGame(ALICE, 2);
        long good = finishedGame(ALICE, 6);
        jdbc.update("update historical_game set result = '{\"standings\":\"oops\"}'::jsonb where id = ?", bad);

        JsonNode p = profile(ALICE);
        assertEquals(1, p.get("settleFailures").asInt());
        assertEquals(1, p.get("totals").get("games").asInt(), "only the good game was counted");
        assertEquals(0, count("select count(*) from historical_game_award where game_id = ?", bad));
        assertEquals(1, count("select count(*) from historical_game_award where game_id = ?", good));
        assertTrue(award(ALICE, good).get("settled").asBoolean());

        JsonNode aw = award(ALICE, bad);
        assertFalse(aw.get("settled").asBoolean(), "200 with settled:false, never a 500");
        assertEquals(0, aw.get("xp").asInt());
        assertEquals(0, aw.get("lines").size());
        assertEquals(0, aw.get("total").asInt());
    }

    // ---- access -----------------------------------------------------------------------------------------

    @Test
    void everyRouteRefusesAMissingOrBlankIdentityTheSameWay() throws Exception {
        long id = finishedGame(ALICE, 3);
        for (String header : new String[]{null, "", "   "}) {
            var p = get("/api/history/profile");
            var a = get("/api/history/games/" + id + "/award");
            var s = post("/api/history/profile/seen").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"codes\":[],\"level\":null,\"momentsGameId\":null}");
            if (header != null) {
                p.header("X-Sleeper-User", header);
                a.header("X-Sleeper-User", header);
                s.header("X-Sleeper-User", header);
            }
            mvc.perform(p).andExpect(status().isUnauthorized());
            mvc.perform(a).andExpect(status().isUnauthorized());
            mvc.perform(s).andExpect(status().isUnauthorized());
        }
        // and nothing was settled by the refused calls
        assertEquals(0, count("select count(*) from historical_game_award where sleeper_user_id = ?", ALICE));
    }

    @Test
    void aStrangerGets404OnAnotherUsersGameAndNothingOnTheirProfile() throws Exception {
        long done = finishedGame(ALICE, 3);
        long drafting = games.create(ALICE, "NBA", 5, HistoricalSyntheticSeason.S).id();
        profile(ALICE);

        mvc.perform(get("/api/history/games/" + done + "/award").header("X-Sleeper-User", BOB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/history/games/" + 987654321L + "/award").header("X-Sleeper-User", ALICE))
                .andExpect(status().isNotFound());
        // ownership is decided before status: a stranger on a DRAFTING game is 404, the owner is 409
        mvc.perform(get("/api/history/games/" + drafting + "/award").header("X-Sleeper-User", BOB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/history/games/" + drafting + "/award").header("X-Sleeper-User", ALICE))
                .andExpect(status().isConflict());

        JsonNode bob = profile(BOB);
        assertEquals(0, bob.get("xp").asInt());
        assertEquals(0, bob.get("totals").get("games").asInt());
        for (JsonNode b : bob.get("badges")) assertFalse(b.get("unlocked").asBoolean());
        assertEquals(21, bob.get("badges").size(), "the whole catalog, in catalog order");
        assertEquals("FIRST_GAME", bob.get("badges").get(0).get("code").asText());
    }

    // ---- seen is monotonic ---------------------------------------------------------------------------------

    @Test
    void seenOnlyMovesForward() throws Exception {
        long id = finishedGame(ALICE, 3);
        JsonNode p = profile(ALICE);
        Set<String> unseen = new HashSet<>();
        for (JsonNode b : p.get("badges")) if (b.get("unlocked").asBoolean() && !b.get("seen").asBoolean()) {
            unseen.add(b.get("code").asText());
        }
        assertTrue(unseen.contains("FIRST_GAME"));
        int level = p.get("level").asInt();

        seen(ALICE, "{\"codes\":[\"FIRST_GAME\",\"NOT_A_BADGE\",\"CHAMPION\"],\"level\":null,\"momentsGameId\":" + id + "}");
        java.time.OffsetDateTime stamped = jdbc.queryForObject("select seen_at from historical_achievement"
                + " where sleeper_user_id = ? and code = 'FIRST_GAME'", java.time.OffsetDateTime.class, ALICE);
        assertNotNull(stamped);
        assertTrue(award(ALICE, id).get("momentsSeen").asBoolean());

        // a second mark does not move the time
        Thread.sleep(20);
        seen(ALICE, "{\"codes\":[\"FIRST_GAME\"],\"level\":null,\"momentsGameId\":null}");
        assertEquals(stamped, jdbc.queryForObject("select seen_at from historical_achievement where"
                + " sleeper_user_id = ? and code = 'FIRST_GAME'", java.time.OffsetDateTime.class, ALICE));
        // a code the caller never unlocked created nothing
        assertEquals(0, count("select count(*) from historical_achievement where sleeper_user_id = ? and code in"
                + " ('NOT_A_BADGE')", ALICE));

        // the level: raised, clamped to the real level, never lowered
        seen(ALICE, "{\"codes\":[],\"level\":999,\"momentsGameId\":null}");
        assertEquals(level, profile(ALICE).get("seenLevel").asInt(), "clamped to the actual level");
        seen(ALICE, "{\"codes\":[],\"level\":1,\"momentsGameId\":null}");
        assertEquals(level, profile(ALICE).get("seenLevel").asInt(), "never lowered");

        // another user cannot mark Alice's moments or her unseen badges
        long second = finishedGame(ALICE, 4);
        profile(ALICE);
        seen(BOB, "{\"codes\":[\"WINS_1\",\"FIRST_GAME\"],\"level\":50,\"momentsGameId\":" + second + "}");
        assertEquals(0, count("select count(*) from historical_game_award where game_id = ? and moments_seen_at is not null",
                second), "Bob's mark matched nothing of Alice's");
        assertEquals(0, count("select count(*) from historical_achievement where sleeper_user_id = ?", BOB));
        assertEquals(1, count("select seen_level from historical_profile where sleeper_user_id = ?", BOB),
                "Bob's level is clamped to his own (1)");
    }
}
