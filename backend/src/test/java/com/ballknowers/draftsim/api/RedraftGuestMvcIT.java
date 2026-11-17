package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.ClaimResult;
import com.ballknowers.draftsim.historical.HistoricalAchievementRepository;
import com.ballknowers.draftsim.historical.HistoricalDailyService;
import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.DailyRow;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import com.ballknowers.draftsim.historical.NbaHistoricalSport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spec 074 T004 to T010: signed-out play against Postgres, over the made-up season ({@link HistoricalSyntheticSeason},
 * 1895, spied so it counts as offered) and made-up puzzle days in 1895, so no real day's row is touched. The guest
 * allowance, the chosen-season flag and what it keeps out of achievements, the claim, and the refusals.
 *
 * <p>The games of the day are started through the service with a made-up date (the HTTP route always means today).
 * SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedraftGuestMvcIT {

    private static final String G1 = "00000074-0000-4000-8000-0000000000a1";
    private static final String G2 = "00000074-0000-4000-8000-0000000000a2";
    private static final String G3 = "00000074-0000-4000-8000-0000000000a3";
    private static final String K1 = "guest:" + G1, K2 = "guest:" + G2, K3 = "guest:" + G3;
    private static final String ALICE = "it-rguest-alice", BOB = "it-rguest-bob";
    private static final LocalDate DAY = LocalDate.of(1895, 3, 10), DAY2 = LocalDate.of(1895, 3, 11);

    private static String url() {
        return System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
    }

    @BeforeAll
    static void requiresPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url(), "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping guest IT");
    }

    @MockitoSpyBean private NbaHistoricalSport nba;
    @MockitoSpyBean private HistoricalGameRepository gameRepo;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameService gameService;
    @Autowired private HistoricalDailyService daily;
    @Autowired private HistoricalAchievementRepository achievementRepo;

    @BeforeEach
    void freshWorld() {
        cleanup(jdbc);
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
        Mockito.doReturn(data.offeredSeasons("NBA", 1893, 1895)).when(nba).offeredSeasons();
        Mockito.doReturn(1893).when(nba).firstOffered();
        Mockito.doReturn(1895).when(nba).lastOffered();
        deal(DAY);
        deal(DAY2);
    }

    private void deal(LocalDate day) {
        gameRepo.insertDailyPuzzleIfAbsent(new DailyRow("NBA", day, HistoricalSyntheticSeason.S, 74L));
    }

    private static void cleanup(JdbcTemplate j) {
        j.update("delete from historical_achievement where sleeper_user_id like 'it-rguest-%'");
        j.update("delete from historical_profile where sleeper_user_id like 'it-rguest-%'");
        j.update("delete from historical_game where sleeper_user_id like 'it-rguest-%' or sleeper_user_id like 'guest:00000074-%'");
        j.update("delete from historical_daily where daily_date < '1900-01-01'");
    }

    @AfterAll
    static void removeEverything() {
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url(), "draftsim", "draftsim"));
            cleanup(j);
            HistoricalSyntheticSeason.clean(j);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was written either.
        }
    }

    // ---- http helpers ---------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String sleeper, String guest) {
        if (sleeper != null) b.header("X-Sleeper-User", sleeper);
        if (guest != null) b.header("X-Guest-Id", guest);
        return b;
    }

    private MvcResult send(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    private MvcResult create(String sleeper, String guest, String body) throws Exception {
        return send(as(post("/api/history/games").contentType(MediaType.APPLICATION_JSON).content(body), sleeper, guest));
    }

    private JsonNode bodyOf(MvcResult r) throws Exception {
        String t = r.getResponse().getContentAsString();
        return t.isBlank() ? null : json.readTree(t);
    }

    private static final String CHOSEN = "{\"sport\":\"NBA\",\"userSlot\":5,\"seasonStart\":1895}";

    private boolean chosenFlag(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select chosen_season from historical_game where id = ?",
                Boolean.class, id));
    }

    private String ownerOf(long id) {
        return jdbc.queryForObject("select sleeper_user_id from historical_game where id = ?", String.class, id);
    }

    // ---- quickstart 2: the five steps ---------------------------------------------------------------------

    @Test
    void aGuestPlaysTheDailyThenOneChosenSeasonThenIsRefusedThenSignsInAndKeepsBoth() throws Exception {
        // 1. the game of the day is open to a guest, and is not a chosen season
        long dailyId = daily.start(K1, "NBA", DAY).state().id();
        assertFalse(chosenFlag(dailyId), "a dealt game is never chosen");
        assertEquals(K1, ownerOf(dailyId));

        // 2. one chosen season is allowed, and is flagged
        MvcResult first = create(null, G1, CHOSEN);
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        JsonNode state = bodyOf(first);
        long chosenId = state.get("id").asLong();
        assertTrue(state.get("chosenSeason").asBoolean());
        assertFalse(state.get("dailyDate").isTextual());
        assertTrue(chosenFlag(chosenId));
        assertEquals(K1, ownerOf(chosenId), "stored under the guest owner key, in lowercase");

        // 3. a second chosen season is refused, with the code the page reads
        MvcResult second = create(null, G1, CHOSEN);
        assertEquals(403, second.getResponse().getStatus());
        assertEquals("GUEST_LIMIT", bodyOf(second).get("code").asText());
        assertTrue(bodyOf(second).get("error").asText().length() > 10, "a sentence in the controller's existing 'error' field");

        // 4. a random season is refused too, and a different guest is not affected by the first one's allowance
        MvcResult random = create(null, G1, "{\"sport\":\"NBA\"}");
        assertEquals(403, random.getResponse().getStatus());
        assertEquals("GUEST_LIMIT", bodyOf(random).get("code").asText());
        assertEquals(201, create(null, G2, CHOSEN).getResponse().getStatus());

        // 5. signing in moves both games, once
        MvcResult claim = send(as(post("/api/history/claim"), ALICE, G1));
        assertEquals(200, claim.getResponse().getStatus());
        assertEquals(2, bodyOf(claim).get("moved").asInt());
        assertEquals(0, bodyOf(claim).get("leftBehind").asInt());
        assertEquals(ALICE, ownerOf(dailyId));
        assertEquals(ALICE, ownerOf(chosenId));
        assertTrue(chosenFlag(chosenId), "a claimed chosen-season game is still chosen");
        assertEquals(0, bodyOf(send(as(get("/api/history/games"), null, G1))).size(), "the guest id holds nothing now");
        assertEquals(2, bodyOf(send(as(get("/api/history/games"), ALICE, null))).size());
        // a second call moves nothing (idempotent)
        assertEquals(0, bodyOf(send(as(post("/api/history/claim"), ALICE, G1))).get("moved").asInt());
    }

    @Test
    void sixParallelChosenSeasonCreatesFromOneGuestMakeExactlyOneGame() throws Exception {
        // CR-12: the allowance holds under a race (the advisory lock sits inside the create transaction).
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> create(null, G1.toUpperCase(), CHOSEN).getResponse().getStatus()));
            int created = 0, refused = 0;
            for (Future<Integer> f : fs) {
                int status = f.get();
                if (status == 201) created++;
                else if (status == 403) refused++;
                else throw new AssertionError("unexpected status " + status);
            }
            assertEquals(1, created);
            assertEquals(5, refused);
            assertEquals(1, jdbc.queryForObject("select count(*) from historical_game where sleeper_user_id = ?",
                    Integer.class, K1).intValue(), "one row, whatever the case of the header");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAbandonedChosenGameStillSpendsTheGuestsAllowance() throws Exception {
        long id = bodyOf(create(null, G1, CHOSEN)).get("id").asLong();
        assertEquals(204, send(as(post("/api/history/games/" + id + "/abandon"), null, G1)).getResponse().getStatus());
        MvcResult again = create(null, G1, CHOSEN);
        assertEquals(403, again.getResponse().getStatus());
        assertEquals("GUEST_LIMIT", bodyOf(again).get("code").asText());
    }

    @Test
    void aSignedInPlayerIsNotLimitedAndMayPickAnySeasonAnyNumberOfTimes() throws Exception {
        assertEquals(201, create(ALICE, null, CHOSEN).getResponse().getStatus());
        assertEquals(201, create(ALICE, null, CHOSEN).getResponse().getStatus());
        assertEquals(201, create(ALICE, null, "{\"sport\":\"NBA\",\"userSlot\":2}").getResponse().getStatus(),
                "a random season: the made-up season is the only one offered");
    }

    @Test
    void aChosenSeasonMustBeOfferedAndInsideTheConfiguredRange() throws Exception {
        for (String body : new String[]{"{\"sport\":\"NBA\",\"seasonStart\":1950}",
                "{\"sport\":\"NBA\",\"seasonStart\":-1}",
                "{\"sport\":\"NFL\",\"seasonStart\":1895}",     // football has no such season
                "{\"sport\":\"NBA\",\"seasonStart\":1894}"}) {  // player lines exist for 1894 but it is not a season row
            assertEquals(400, create(ALICE, null, body).getResponse().getStatus(), body);
            assertEquals(400, create(null, G3, body).getResponse().getStatus(), body + " (a guest: 400 before the allowance)");
        }
        // imported and listed as offered by the data, but outside the sport's configured first..last range
        Mockito.doReturn(1896).when(nba).firstOffered();
        Mockito.doReturn(1899).when(nba).lastOffered();
        assertEquals(400, create(ALICE, null, CHOSEN).getResponse().getStatus(),
                "1895 is in the offered list but outside firstOffered..lastOffered");
        assertEquals(0, jdbc.queryForObject("select count(*) from historical_game where sleeper_user_id = ?",
                Integer.class, ALICE).intValue());
    }

    // ---- identity and ownership ---------------------------------------------------------------------------

    @Test
    void theAbsentIdentityIsTheSameRefusalAsAWrongOne() throws Exception {
        String[][] noIdentity = {{null, null}, {"", null}, {"  ", null}, {null, ""}, {null, "  "}, {null, "nope"},
                {null, G1.replace('-', '_')}};
        for (String[] h : noIdentity) {
            assertEquals(401, send(as(get("/api/history/games"), h[0], h[1])).getResponse().getStatus(),
                    "sleeper=" + h[0] + " guest=" + h[1]);
            assertEquals(401, create(h[0], h[1], CHOSEN).getResponse().getStatus());
        }
        // a Sleeper id that claims the guest namespace is a 400, whoever sends it
        assertEquals(400, send(as(get("/api/history/games"), K1, null)).getResponse().getStatus());
        assertEquals(400, send(as(get("/api/history/games"), K1, G1)).getResponse().getStatus());
    }

    @Test
    void aGuestCannotReadOrMoveAnotherOwnersDraftingGame() throws Exception {
        long aliceGame = bodyOf(create(ALICE, null, CHOSEN)).get("id").asLong();
        long guestGame = bodyOf(create(null, G1, CHOSEN)).get("id").asLong();
        // another guest, a signed-in stranger, and the public route: the same 404 for a game still drafting
        for (MockHttpServletRequestBuilder b : List.of(
                as(get("/api/history/games/" + aliceGame), null, G2),
                as(get("/api/history/games/" + aliceGame), BOB, null),
                as(get("/api/history/games/" + guestGame), null, G2),
                as(get("/api/history/games/" + guestGame), ALICE, null),
                get("/api/history/public/games/" + aliceGame),
                get("/api/history/public/games/" + guestGame))) {
            assertEquals(404, send(b).getResponse().getStatus());
        }
        for (String act : new String[]{"pick", "auto", "abandon"}) {
            assertEquals(404, send(as(post("/api/history/games/" + aliceGame + "/" + act)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"playerId\":1,\"scope\":\"PICK\"}"), null, G1))
                    .getResponse().getStatus(), act);
        }
        // and a guest sees only their own list
        JsonNode mine = bodyOf(send(as(get("/api/history/games"), null, G1)));
        assertEquals(1, mine.size());
        assertEquals(guestGame, mine.get(0).get("id").asLong());
        assertTrue(mine.get(0).get("chosenSeason").asBoolean());
    }

    // ---- what a chosen season keeps out -------------------------------------------------------------------

    @Test
    void aGuestIsRefusedTheProfileTheAwardAndTheSeenMarksAfterTheIdentityCheck() throws Exception {
        for (MockHttpServletRequestBuilder b : List.of(
                as(get("/api/history/profile"), null, G1),
                as(get("/api/history/games/1/award"), null, G1),
                as(post("/api/history/profile/seen").contentType(MediaType.APPLICATION_JSON).content("{}"), null, G1))) {
            MvcResult r = send(b);
            assertEquals(403, r.getResponse().getStatus());
            assertEquals("GUEST_LIMIT", bodyOf(r).get("code").asText());
        }
        for (MockHttpServletRequestBuilder b : List.of(get("/api/history/profile"), get("/api/history/games/1/award"))) {
            assertEquals(401, send(b).getResponse().getStatus(), "no identity at all is still a 401, not a 403");
        }
    }

    @Test
    void aFinishedChosenSeasonGameEarnsNothingEvenWhenItWouldWinTheTitle() throws Exception {
        // Alice plays a dealt game (it counts) and a chosen season (it does not).
        long dealt = daily.start(ALICE, "NBA", DAY).state().id();
        gameService.auto(dealt, HistoricalGameService.Scope.FINISH, ALICE);
        long chosen = bodyOf(create(ALICE, null, CHOSEN)).get("id").asLong();
        assertEquals("COMPLETE", bodyOf(send(as(post("/api/history/games/" + chosen + "/auto")
                .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"FINISH\"}"), ALICE, null))).get("status").asText());
        // make the chosen game a title run: the strongest case for the rule
        jdbc.update("update historical_game set result = jsonb_set(result, '{userOutcome}', '\"CHAMPION\"') where id = ?", chosen);

        JsonNode award = bodyOf(send(as(get("/api/history/games/" + chosen + "/award"), ALICE, null)));
        assertTrue(award.get("notCounted").asBoolean());
        assertFalse(award.get("settled").asBoolean());
        assertEquals(0, award.get("xp").asInt());
        assertEquals(0, award.get("lines").size());

        JsonNode profile = bodyOf(send(as(get("/api/history/profile"), ALICE, null)));
        assertEquals(1, profile.get("totals").get("games").asInt(), "only the dealt game counts");
        assertEquals(0, profile.get("totals").get("titles").asInt(), "the chosen game's title adds nothing");
        assertEquals(0, achievementRepo.unsettled(ALICE).size(), "it is not even waiting to be settled");
        long xp = profile.get("xp").asLong();

        // and the dealt game DID settle: its award is a real one, not 'notCounted'
        JsonNode dealtAward = bodyOf(send(as(get("/api/history/games/" + dealt + "/award"), ALICE, null)));
        assertFalse(dealtAward.get("notCounted").asBoolean());
        assertTrue(dealtAward.get("settled").asBoolean());
        assertTrue(xp > 0, "the dealt game earned something");
        assertEquals(dealtAward.get("total").asLong(), xp, "and the profile's XP is that game's alone");
    }

    @Test
    void aClaimedChosenGameStillEarnsNothingAndAClaimedDailyDoesSettle() throws Exception {
        long dealt = daily.start(K1, "NBA", DAY).state().id();
        gameService.auto(dealt, HistoricalGameService.Scope.FINISH, K1);
        long chosen = bodyOf(create(null, G1, CHOSEN)).get("id").asLong();
        gameService.auto(chosen, HistoricalGameService.Scope.FINISH, K1);
        assertEquals(200, send(as(post("/api/history/claim"), ALICE, G1)).getResponse().getStatus());

        JsonNode profile = bodyOf(send(as(get("/api/history/profile"), ALICE, null)));
        assertEquals(1, profile.get("totals").get("games").asInt());
        assertTrue(bodyOf(send(as(get("/api/history/games/" + chosen + "/award"), ALICE, null))).get("notCounted").asBoolean());
        assertTrue(bodyOf(send(as(get("/api/history/games/" + dealt + "/award"), ALICE, null))).get("settled").asBoolean());
    }

    // ---- claim ----------------------------------------------------------------------------------------------

    @Test
    void claimNeedsBothHeadersWellFormed() throws Exception {
        String[][] bad = {{null, null}, {ALICE, null}, {null, G1}, {"", G1}, {"  ", G1}, {ALICE, ""}, {ALICE, "nope"},
                {K1, G1}, {"guest:anything", G1}};
        for (String[] h : bad) {
            assertEquals(400, send(as(post("/api/history/claim"), h[0], h[1])).getResponse().getStatus(),
                    "sleeper=" + h[0] + " guest=" + h[1]);
        }
    }

    @Test
    void whenTheSleeperIdAlreadyPlayedThatDaysDailyTheGuestsStaysBehindAndIsCounted() throws Exception {
        long guestDaily = daily.start(K1, "NBA", DAY).state().id();
        long guestOther = daily.start(K1, "NBA", DAY2).state().id();
        long guestChosen = bodyOf(create(null, G1, CHOSEN)).get("id").asLong();
        long aliceDaily = daily.start(ALICE, "NBA", DAY).state().id();

        ClaimResult r = gameService.claim(K1, ALICE);
        assertEquals(2, r.moved(), "the other day's daily and the chosen season");
        assertEquals(1, r.leftBehind(), "that day's daily: Alice already has one");
        assertEquals(K1, ownerOf(guestDaily), "left behind under the guest key");
        assertEquals(ALICE, ownerOf(guestOther));
        assertEquals(ALICE, ownerOf(guestChosen));
        assertEquals(ALICE, ownerOf(aliceDaily));
        assertEquals(1, jdbc.queryForObject("select count(*) from historical_game where sleeper_user_id = ? and daily_date = ?",
                Integer.class, ALICE, DAY).intValue(), "one daily a day per person still holds");
        // again: nothing more moves, and the same one stays behind
        ClaimResult again = gameService.claim(K1, ALICE);
        assertEquals(0, again.moved());
        assertEquals(1, again.leftBehind());
    }

    @Test
    void twoGuestsClaimingIntoOneSleeperIdAtOnceNeverFailAndNeverBreakOneDailyADay() throws Exception {
        daily.start(K1, "NBA", DAY);
        daily.start(K2, "NBA", DAY);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ClaimResult>> fs = new ArrayList<>();
            fs.add(pool.submit(() -> gameService.claim(K1, ALICE)));
            fs.add(pool.submit(() -> gameService.claim(K2, ALICE)));
            int moved = 0, left = 0;
            for (Future<ClaimResult> f : fs) {
                ClaimResult c = f.get();   // an exception here is the 500 the review feared
                moved += c.moved();
                left += c.leftBehind();
            }
            assertEquals(1, moved, "one of them wins the day");
            assertEquals(1, left, "the other is told, not failed");
            assertEquals(1, jdbc.queryForObject("select count(*) from historical_game where sleeper_user_id = ?",
                    Integer.class, ALICE).intValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aUniqueIndexRaceDuringClaimIsRunAgainNotA500() throws Exception {
        long guestDaily = daily.start(K1, "NBA", DAY).state().id();
        // The first attempt loses a race on the daily index (the Sleeper id started that day's game in another tab at
        // the same instant); the second one runs for real and now sees the collision.
        Mockito.doThrow(new DuplicateKeyException("historical_game_daily_once")).doCallRealMethod()
                .when(gameRepo).moveOwner(K1, ALICE);
        daily.start(ALICE, "NBA", DAY);
        ClaimResult r = gameService.claim(K1, ALICE);
        assertEquals(0, r.moved());
        assertEquals(1, r.leftBehind());
        assertEquals(K1, ownerOf(guestDaily));
        Mockito.verify(gameRepo, Mockito.times(2)).moveOwner(K1, ALICE);

        // and a claim that keeps losing is still a normal answer
        Mockito.reset(gameRepo);
        Mockito.doThrow(new DuplicateKeyException("again")).when(gameRepo).moveOwner(K1, BOB);
        ClaimResult stuck = gameService.claim(K1, BOB);
        assertEquals(0, stuck.moved());
        assertEquals(1, stuck.leftBehind());
    }

    @Test
    void theHttpClaimRouteAnswersAClaimResult() throws Exception {
        daily.start(K3, "NBA", DAY);
        MvcResult r = send(as(post("/api/history/claim"), BOB, G3));
        assertEquals(200, r.getResponse().getStatus());
        List<String> fields = new ArrayList<>();
        bodyOf(r).fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("moved", "leftBehind"), fields);
        assertEquals(1, bodyOf(r).get("moved").asInt());
    }

    @Test
    void theAllowanceCountsANonDailyGameInAnyStatus() {
        assertEquals(0, gameRepo.countNonDaily(K1));
        daily.start(K1, "NBA", DAY);
        assertEquals(0, gameRepo.countNonDaily(K1), "a game of the day never counts against the allowance");
        long id = gameService.createFor(K1, "NBA", 5, 1895).id();
        assertEquals(1, gameRepo.countNonDaily(K1));
        gameService.abandon(id, K1);
        assertEquals(1, gameRepo.countNonDaily(K1));
        assertThrows(com.ballknowers.draftsim.historical.GuestLimitException.class,
                () -> gameService.createFor(K1, "NBA", 5, 1895));
        assertThrows(com.ballknowers.draftsim.historical.GuestLimitException.class,
                () -> gameService.createFor(K1, "NBA", 5, null));
    }

    @Test
    void theTableForbidsAChosenDailyGame() {
        long id = gameService.create(ALICE, "NBA", 5, HistoricalSyntheticSeason.S).id();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "update historical_game set chosen_season = true, daily_date = ? where id = ?", DAY, id));
    }
}
