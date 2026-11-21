package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalDailyService;
import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.DailyRow;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalGameState;
import com.ballknowers.draftsim.historical.HistoricalPlayerCard;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 071: the game of the day against real Postgres, on the made-up season
 * ({@link HistoricalSyntheticSeason}, 1895) and made-up puzzle days in 1895, so no real day's row is touched.
 * The service methods take the date; the two HTTP routes (which use today) are checked for identity, preflight,
 * the sport rule and their shape only.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricalDailyMvcIT {

    private static final String ALICE = "it-hdaily-alice", BOB = "it-hdaily-bob";
    private static final LocalDate DAY = LocalDate.of(1895, 3, 10);
    private static final long SEED = 71L;

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
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping daily game IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameRepository games;
    @Autowired private HistoricalGameService gameService;
    @Autowired private HistoricalDailyService daily;

    private static boolean seeded;

    private void ensureSeeded() {
        if (seeded) return;
        cleanup(jdbc);
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
        seeded = true;
    }

    /** Deals the made-up puzzle for {@code day}: the synthetic season, a fixed seed. */
    private void deal(LocalDate day) {
        ensureSeeded();
        games.insertDailyPuzzleIfAbsent(new DailyRow("NBA", day, HistoricalSyntheticSeason.S, SEED));
    }

    private static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-hdaily-%'");
        jdbc.update("delete from historical_daily where daily_date < '1900-01-01'");
    }

    @AfterAll
    static void removeEverythingThisWrote() {
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url(), "draftsim", "draftsim"));
            cleanup(j);
            HistoricalSyntheticSeason.clean(j);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was written either.
        }
    }

    private HistoricalDailyService.DailyView nba(String user, LocalDate day) {
        return daily.status(user, day).stream().filter(v -> v.sport().equals("NBA")).findFirst().orElseThrow();
    }

    private static List<Long> boardOrder(HistoricalGameState s) {
        // Everything not yet picked, in board order, plus what was picked: compare the untouched tail.
        return s.available().stream().map(HistoricalPlayerCard::id).toList();
    }

    // ---- the rules --------------------------------------------------------------------------------------

    @Test
    void twoPlayersOfTheSameDayGetTheSameSeasonSeedAndBoard() {
        deal(DAY);
        try {
            var a = daily.start(ALICE, "NBA", DAY);
            var b = daily.start(BOB, "NBA", DAY);
            assertTrue(a.created() && b.created());
            assertEquals("1895-96", a.state().season().label());
            assertEquals(a.state().season().label(), b.state().season().label());
            assertEquals(DAY.toString(), a.state().dailyDate());
            assertNull(a.state().dailyNumber(), "spec 078: a made-up 1895 day is before day 1, so it has no number");

            var rowA = games.find(a.state().id()).orElseThrow();
            var rowB = games.find(b.state().id()).orElseThrow();
            assertEquals(SEED, rowA.rngSeed(), "the day's seed, not a fresh random one");
            assertEquals(rowA.rngSeed(), rowB.rngSeed());
            assertEquals(rowA.boardJson(), rowB.boardJson(), "the same board snapshot for everyone that day");
        } finally {
            cleanup(jdbc);
        }
    }

    @Test
    void startingTheSameDayTwiceReturnsTheFirstGame() {
        deal(DAY);
        try {
            var first = daily.start(ALICE, "NBA", DAY);
            var second = daily.start(ALICE, "NBA", DAY);
            assertTrue(first.created());
            assertFalse(second.created());
            assertEquals(first.state().id(), second.state().id());
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from historical_game where sleeper_user_id = ? and daily_date = ?",
                    Integer.class, ALICE, java.sql.Date.valueOf(DAY)));
        } finally {
            cleanup(jdbc);
        }
    }

    @Test
    void theDatabaseItselfRefusesASecondDailyGameForTheSameUserSportAndDay() {
        deal(DAY);
        try {
            daily.start(ALICE, "NBA", DAY);
            assertThrows(org.springframework.dao.DuplicateKeyException.class,
                    () -> gameService.createDaily(ALICE, "NBA", HistoricalSyntheticSeason.S, SEED, DAY),
                    "the unique index is the guard, not the service's look-before-insert");
        } finally {
            cleanup(jdbc);
        }
    }

    @Test
    void aDailyGameCannotBeAbandonedButFreePlayStillCan() throws Exception {
        deal(DAY);
        try {
            long dailyId = daily.start(ALICE, "NBA", DAY).state().id();
            send(ALICE, post("/api/history/games/" + dailyId + "/abandon"), null, 409);
            assertEquals("DRAFTING", games.find(dailyId).orElseThrow().status());

            HistoricalGameState free = gameService.create(ALICE, "NBA", 4, HistoricalSyntheticSeason.S);
            assertNull(free.dailyDate(), "free play carries no puzzle date");
            send(ALICE, post("/api/history/games/" + free.id() + "/abandon"), null, 204);
        } finally {
            cleanup(jdbc);
        }
    }

    @Test
    void theStatusFollowsTheGameFromNotStartedToFinishedAndTheStreakCountsFinishedDays() {
        LocalDate d1 = DAY, d2 = DAY.plusDays(1), d3 = DAY.plusDays(2);
        deal(d1);
        deal(d2);
        deal(d3);
        try {
            var before = nba(ALICE, d1);
            assertNull(before.gameId());
            assertNull(before.gameStatus());
            assertNull(before.draftScore());
            assertEquals(0, before.streak());
            assertEquals("1895-96", before.seasonLabel());
            assertEquals(d1.toString(), before.date());
            // The next day starts at midnight US Eastern: 05:00 UTC in March 1895 (standard time, as tzdata has it).
            Instant next = Instant.parse(before.nextAt());
            assertEquals(d2, next.atZone(HistoricalDailyService.ZONE).toLocalDate());
            assertEquals(0, next.atZone(HistoricalDailyService.ZONE).toLocalTime().toSecondOfDay());

            long id = daily.start(ALICE, "NBA", d1).state().id();
            var drafting = nba(ALICE, d1);
            assertEquals(id, drafting.gameId());
            assertEquals("DRAFTING", drafting.gameStatus());
            assertNull(drafting.draftScore(), "no score while drafting");
            assertEquals(0, drafting.streak(), "a started game is not a finished one");

            gameService.auto(id, HistoricalGameService.Scope.FINISH, ALICE);
            var done = nba(ALICE, d1);
            assertEquals("COMPLETE", done.gameStatus());
            assertEquals(1, done.streak());
            // The score on the status row is the same number the games list carries for that game.
            Integer listed = gameService.list(ALICE).stream().filter(g -> g.id() == id).findFirst().orElseThrow()
                    .draftScore();
            assertNotNull(listed, "a finished game has a draft score");
            assertEquals(listed, done.draftScore());
            assertEquals(d1.toString(), gameService.list(ALICE).get(0).dailyDate());

            // Day two not played yet: the run from day one still stands.
            assertEquals(1, nba(ALICE, d2).streak());
            gameService.auto(daily.start(ALICE, "NBA", d2).state().id(), HistoricalGameService.Scope.FINISH, ALICE);
            assertEquals(2, nba(ALICE, d2).streak());
            // Day three, and day two was finished: still two until today's is done. Bob has none of this.
            assertEquals(2, nba(ALICE, d3).streak());
            assertEquals(2, nba(ALICE, d3).bestStreak());
            assertEquals(0, nba(BOB, d3).streak());
            // Skip day three entirely: on day four the run is over, and the best is remembered.
            deal(DAY.plusDays(3));
            assertEquals(0, nba(ALICE, DAY.plusDays(3)).streak());
            assertEquals(2, nba(ALICE, DAY.plusDays(3)).bestStreak());
        } finally {
            cleanup(jdbc);
        }
    }

    @Test
    void theListScoreIsTheSameArithmeticOnTheFrozenResult() throws Exception {
        ensureSeeded();
        try {
            long id = gameService.create(ALICE, "NBA", 6, HistoricalSyntheticSeason.S).id();
            assertNull(gameService.list(ALICE).get(0).draftScore(), "null until COMPLETE");
            gameService.auto(id, HistoricalGameService.Scope.FINISH, ALICE);

            JsonNode h = json.valueToTree(gameService.get(id, ALICE).orElseThrow()).get("result").get("hindsight");
            int expected = (int) Math.floor(100 * h.get("userPointsFor").asDouble() / h.get("pointsFor").asDouble());
            assertEquals(expected, gameService.list(ALICE).get(0).draftScore());
        } finally {
            cleanup(jdbc);
        }
    }

    // ---- the routes -------------------------------------------------------------------------------------

    private JsonNode send(String user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                          String body, int expect) throws Exception {
        if (user != null) req.header("X-Sleeper-User", user);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mvc.perform(req).andExpect(status().is(expect)).andReturn();
        String text = r.getResponse().getContentAsString();
        return text.isBlank() ? null : json.readTree(text);
    }

    @Test
    void bothRoutesNeedAnIdentityAndTheStartNeedsAKnownSport() throws Exception {
        send(null, get("/api/history/daily"), null, 401);
        send(null, post("/api/history/daily"), "{\"sport\":\"NBA\"}", 401);
        send(ALICE, post("/api/history/daily"), "{}", 400);
        send(ALICE, post("/api/history/daily"), "{\"sport\":\"MLB\"}", 400);
        send(ALICE, post("/api/history/daily"), null, 400);
    }

    /** A real browser preflights the POST because of X-Sleeper-User (bug class 6: curl never does). */
    @Test
    void aBrowserPreflightPassesForTheDailyRoute() throws Exception {
        mvc.perform(options("/api/history/daily")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-sleeper-user"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void theStatusRouteAnswersWithOneRowPerOfferedSport() throws Exception {
        try {
            JsonNode rows = send(ALICE, get("/api/history/daily"), null, 200);
            assertTrue(rows.isArray());
            LocalDate today = daily.today();
            for (JsonNode row : rows) {
                assertTrue(List.of("NBA", "NFL").contains(row.get("sport").asText()));
                assertEquals(today.toString(), row.get("date").asText());
                assertFalse(row.get("seasonLabel").asText().isBlank());
                assertTrue(row.get("gameId").isNull(), "this user has started nothing today");
                assertTrue(Instant.parse(row.get("nextAt").asText()).isAfter(Instant.now()));
            }
        } finally {
            cleanup(jdbc);
        }
    }
}
