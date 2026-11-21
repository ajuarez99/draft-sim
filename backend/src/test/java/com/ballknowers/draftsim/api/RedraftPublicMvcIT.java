package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalDailyService;
import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.DailyRow;
import com.ballknowers.draftsim.historical.HistoricalGameService;
import com.ballknowers.draftsim.historical.HistoricalSports;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import com.ballknowers.draftsim.historical.NbaHistoricalSport;
import com.ballknowers.draftsim.historical.SeasonPage;
import com.ballknowers.draftsim.historical.SeasonSummaryService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Spec 074 T015, T016, T012, T014 against Postgres: the public season and game routes over the made-up season
 * ({@link HistoricalSyntheticSeason}, 1895). The basketball sport is spied so that season counts as offered (its real
 * range starts in 1970) and the real offered list never matters; nothing here touches a real season's page or a real
 * day's puzzle.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedraftPublicMvcIT {

    private static final String ALICE = "it-rpub-alice";
    private static final LocalDate DAY = LocalDate.of(1895, 3, 10);

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
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping public Redraft IT");
    }

    @MockitoSpyBean private NbaHistoricalSport nba;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameRepository gameRepo;
    @Autowired private HistoricalGameService gameService;
    @Autowired private HistoricalDailyService daily;
    @Autowired private HistoricalSports sports;
    @Autowired private SeasonSummaryService summaries;

    @BeforeEach
    void freshWorld() {
        cleanup(jdbc);
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
        // The made-up season counts as offered: only 1895 has a season row (its prior lines are 1894 and 1893).
        Mockito.doReturn(data.offeredSeasons("NBA", 1893, 1895)).when(nba).offeredSeasons();
        Mockito.doReturn(1893).when(nba).firstOffered();
        Mockito.doReturn(1895).when(nba).lastOffered();
        summaries.clear();
    }

    private static void cleanup(JdbcTemplate j) {
        j.update("delete from historical_game where sleeper_user_id like 'it-rpub-%'");
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

    private MvcResult call(String path) throws Exception {
        return mvc.perform(get(path)).andReturn();
    }

    private JsonNode ok(String path) throws Exception {
        MvcResult r = call(path);
        assertEquals(200, r.getResponse().getStatus(), path + " -> " + r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString());
    }

    // ---- the season list and one season ---------------------------------------------------------------

    @Test
    void theSeasonListIsWrappedWithASchemaAndNeedsNoIdentity() throws Exception {
        MvcResult r = mvc.perform(get("/api/history/public/seasons").param("sport", "NBA")
                .header("X-Sleeper-User", "guest:garbage")).andReturn();   // the header is never read
        assertEquals(200, r.getResponse().getStatus());
        JsonNode list = json.readTree(r.getResponse().getContentAsString());
        assertEquals(1, list.get("schema").asInt());
        assertEquals("NBA", list.get("sport").asText());
        List<String> slugs = new ArrayList<>();
        for (JsonNode s : list.get("seasons")) {
            assertEquals(s.get("label").asText(), s.get("slug").asText());
            slugs.add(s.get("slug").asText());
        }
        assertEquals(List.of("1895-96"), slugs, "the offered seasons, ascending");
        assertEquals("public, max-age=3600", r.getResponse().getHeader("Cache-Control"));
    }

    @Test
    void aMissingOrUnknownSportIsA400AndAnUnknownSeasonOrSportIsA404() throws Exception {
        assertEquals(400, call("/api/history/public/seasons").getResponse().getStatus());
        assertEquals(400, call("/api/history/public/seasons?sport=XXX").getResponse().getStatus());
        assertEquals(400, call("/api/history/public/seasons?sport=").getResponse().getStatus());
        assertEquals(404, call("/api/history/public/seasons/XXX/1895-96").getResponse().getStatus());
        assertEquals(404, call("/api/history/public/seasons/NBA/1950-51").getResponse().getStatus());
        assertEquals(404, call("/api/history/public/seasons/NBA/1895").getResponse().getStatus(),
                "the bare start year is not an address; the label is");
        assertEquals(404, call("/api/history/public/seasons/NBA/1894-95").getResponse().getStatus(),
                "player lines exist for 1894 but it has no season row: not offered");
        assertEquals(200, call("/api/history/public/seasons/nba/1895-96").getResponse().getStatus(), "sport is case-insensitive");
    }

    @Test
    void aSeasonPageHasTheShapeTheWebMirrorsAndItsListsAreConsistent() throws Exception {
        MvcResult r = call("/api/history/public/seasons/NBA/1895-96");
        assertEquals("public, max-age=3600", r.getResponse().getHeader("Cache-Control"));
        JsonNode p = json.readTree(r.getResponse().getContentAsString());

        List<String> fields = new ArrayList<>();
        p.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("sport", "seasonStart", "label", "slug", "prevSlug", "nextSlug", "teams", "rounds",
                "rosterPositions", "scoringNote", "pointsNote", "rankingLabel", "dataNotes", "excludedStats",
                "statCoverage", "attribution", "boardPlayers", "rankedPlayers", "topOverall", "topByPosition",
                "busts", "steals", "unrankedLeftOut", "dreamRoster", "dreamRosterPoints"), fields,
                "field names and order are what web/src/api.ts mirrors");

        assertEquals("1895-96", p.get("label").asText());
        assertTrue(p.get("prevSlug").isNull(), "the first offered season has no previous");
        assertTrue(p.get("nextSlug").isNull(), "the last offered season has no next");
        assertTrue(p.get("attribution").isNull(), "basketball has no licence line, and null must survive the response");
        assertEquals(12, p.get("teams").asInt());
        assertEquals(14, p.get("rounds").asInt());
        assertTrue(p.get("pointsNote").asText().contains("best single game"),
                "basketball points are a best-game-per-week sum and the payload says so");
        assertEquals(HistoricalSyntheticSeason.PLAYERS, p.get("boardPlayers").asInt());
        assertEquals(HistoricalSyntheticSeason.PLAYERS, p.get("rankedPlayers").asInt(), "every synthetic player has a qualifying line");
        // the era disclosure the game would freeze: the made-up season excludes stl
        assertTrue(p.get("excludedStats").has("stl"));
        assertTrue(p.get("statCoverage").has("pts"));

        JsonNode top = p.get("topOverall");
        assertEquals(12, top.size());
        double prevPts = Double.MAX_VALUE;
        for (JsonNode f : top) {
            assertTrue(f.get("points").asDouble() <= prevPts, "best first");
            prevPts = f.get("points").asDouble();
            assertTrue(f.get("positions").isArray() && f.get("positions").size() >= 1);
            assertTrue(f.get("weeksPlayed").asInt() >= 1);
        }
        assertEquals(1, top.get(0).get("finishRank").asInt());
        assertEquals(14, p.get("dreamRoster").size());
        assertTrue(p.get("dreamRosterPoints").asDouble() > 0);
        for (String kind : new String[]{"busts", "steals"}) {
            assertTrue(p.get(kind).size() <= 5);
            for (JsonNode m : p.get(kind)) {
                int in = m.get("goingInRank").asInt(), out = m.get("finishRank").asInt();
                assertTrue(kind.equals("busts") ? out > in : in > out, kind + " direction: going in " + in + ", finished " + out);
            }
        }
        // every position row is ordered and keyed by a base position, never a roster slot
        for (var it = p.get("topByPosition").fieldNames(); it.hasNext(); ) {
            String pos = it.next();
            assertTrue(List.of("PG", "SG", "SF", "PF", "C").contains(pos), pos);
            assertTrue(p.get("topByPosition").get(pos).size() <= 5);
        }
    }

    @Test
    void aSecondRequestIsServedFromTheCacheAndAnImportClearsIt() throws Exception {
        var sport = sports.get("NBA");
        SeasonPage a = summaries.page(sport, "1895-96").orElseThrow();
        SeasonPage b = summaries.page(sport, "1895-96").orElseThrow();
        assertSame(a, b, "computed once");
        summaries.clear();
        SeasonPage c = summaries.page(sport, "1895-96").orElseThrow();
        assertNotSame(a, c, "recomputed after the cache was cleared");
        assertEquals(a, c, "the same data gives the same page");
        assertTrue(summaries.page(sport, "1950-51").isEmpty());
        assertTrue(summaries.page(sport, null).isEmpty());
    }

    @Test
    void manyCallersForOneColdSeasonShareOneComputation() throws Exception {
        var sport = sports.get("NBA");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<SeasonPage>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<SeasonPage> c = () -> summaries.page(sport, "1895-96").orElseThrow();
                futures.add(pool.submit(c));
            }
            SeasonPage first = futures.get(0).get();
            for (Future<SeasonPage> f : futures) assertSame(first, f.get(), "single flight: one page object for all");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- yesterday (read-only) --------------------------------------------------------------------------

    @Test
    void yesterdayIsReadOnlyAndNamesOnlyADealtPuzzle() throws Exception {
        gameRepo.insertDailyPuzzleIfAbsent(new DailyRow("NBA", DAY, HistoricalSyntheticSeason.S, 7L));
        int before = jdbc.queryForObject("select count(*) from historical_daily", Integer.class);
        // a clock the morning after DAY, so "yesterday" is the made-up puzzle day
        Clock clock = Clock.fixed(ZonedDateTime.of(1895, 3, 11, 9, 0, 0, 0, HistoricalDailyService.ZONE).toInstant(),
                HistoricalDailyService.ZONE);
        HistoricalDailyService svc = new HistoricalDailyService(gameRepo, gameService, sports, clock);
        var rows = svc.yesterday();
        assertEquals(2, rows.size(), "one per sport");
        var nbaRow = rows.stream().filter(r -> r.sport().equals("NBA")).findFirst().orElseThrow();
        assertEquals(DAY.toString(), nbaRow.date());
        assertEquals("1895-96", nbaRow.seasonLabel());
        assertEquals(nbaRow.seasonLabel(), nbaRow.slug());
        var nflRow = rows.stream().filter(r -> r.sport().equals("NFL")).findFirst().orElseThrow();
        assertNull(nflRow.seasonLabel(), "no puzzle was dealt for football that day");
        assertNull(nflRow.slug());
        assertEquals(before, (int) jdbc.queryForObject("select count(*) from historical_daily", Integer.class),
                "reading yesterday never deals a puzzle");

        // and the HTTP route: no identity, one row per sport, nothing written
        JsonNode http = ok("/api/history/public/daily/yesterday");
        assertEquals(2, http.size());
        LocalDate yesterday = LocalDate.now(HistoricalDailyService.ZONE).minusDays(1);
        assertEquals(yesterday.toString(), http.get(0).get("date").asText());
        // spec 078: the day's number rides along whether or not that day's puzzle was dealt
        assertEquals((int) com.ballknowers.draftsim.historical.DailyNumber.of(yesterday),
                http.get(0).get("dailyNumber").asInt());
        assertEquals(before, (int) jdbc.queryForObject("select count(*) from historical_daily", Integer.class));
    }

    // ---- a finished game, for anyone ---------------------------------------------------------------------

    private long finishedDaily(LocalDate onDate) {
        gameRepo.insertDailyPuzzleIfAbsent(new DailyRow("NBA", DAY, HistoricalSyntheticSeason.S, 7L));
        long id = daily.start(ALICE, "NBA", DAY).state().id();
        assertEquals("COMPLETE", gameService.auto(id, HistoricalGameService.Scope.FINISH, ALICE).orElseThrow().status());
        jdbc.update("update historical_game set daily_date = ? where id = ?", onDate, id);
        return id;
    }

    @Test
    void todaysFinishedDailyIsReducedAndYesterdaysIsFull() throws Exception {
        LocalDate today = LocalDate.now(HistoricalDailyService.ZONE);
        long id = finishedDaily(today);

        MvcResult r = call("/api/history/public/games/" + id);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
        String text = r.getResponse().getContentAsString();
        JsonNode g = json.readTree(text);
        assertEquals(today.plusDays(1).atStartOfDay(HistoricalDailyService.ZONE).toInstant().toString(),
                g.get("reducedUntil").asText());
        JsonNode s = g.get("state");
        assertEquals("COMPLETE", s.get("status").asText());
        // spec 078: the reduced copy keeps the day's number (the share preview reads it)
        assertEquals((int) com.ballknowers.draftsim.historical.DailyNumber.of(today), s.get("dailyNumber").asInt());
        assertEquals(0, s.get("picks").size(), "no picks while the day's answer key is still today's");
        assertEquals(0, s.get("available").size());
        JsonNode res = s.get("result");
        assertTrue(res.get("userFinish").asInt() >= 1);
        assertEquals(1, res.get("standings").size(), "only the player's own record");
        assertEquals(0, res.get("weeks").size());
        assertEquals(0, res.get("lineups").size());
        assertEquals(0, res.get("hindsight").get("picks").size());
        assertFalse(res.get("hindsight").has("pointsFor"), "the best roster's total is today's answer: not sent");
        assertTrue(res.get("hindsight").has("userPointsFor"), "the player's own total stays");
        assertTrue(res.get("hindsight").has("draftScore"), "the already-computed draft score ships instead");
        assertFalse(res.has("biggestBust") || res.has("bestStealPassedOn") || res.has("hindsightSeason"));
        assertTrue(res.get("pickFinishes").size() > 0, "the pick squares stay");
        for (JsonNode pf : res.get("pickFinishes")) {
            assertFalse(pf.has("player"), "a square carries no player");
            assertFalse(pf.has("seasonValue"));
            // CR-03: a mark, never the raw position or exact finish rank: those resolve the pick to a player on the
            // public season page (today's season page is the day's answer key).
            assertTrue(pf.has("pickNo"));
            assertTrue(java.util.Set.of("ahead", "same", "behind").contains(pf.get("mark").asText()), "mark: " + pf);
            for (String leaked : new String[]{"position", "draftedPositionRank", "finishPositionRank", "positionPlayers"}) {
                assertFalse(pf.has(leaked), leaked + " must not be sent in a reduced row: " + pf);
            }
        }
        assertNoOwner(text);

        // The same game dated yesterday is not the day's answer key any more.
        jdbc.update("update historical_game set daily_date = ? where id = ?", today.minusDays(1), id);
        JsonNode full = ok("/api/history/public/games/" + id);
        assertTrue(full.get("reducedUntil").isNull());
        assertEquals(168, full.get("state").get("picks").size());
        assertTrue(full.get("state").get("result").get("weeks").size() > 0);
        assertTrue(full.get("state").get("result").get("lineups").size() > 0);
        assertTrue(full.get("state").get("result").get("hindsight").get("picks").size() > 0);
        assertNoOwner(call("/api/history/public/games/" + id).getResponse().getContentAsString());
    }

    @Test
    void aFreePlayGameIsFullAtOnceAndNoOtherStateIsReadable() throws Exception {
        long done = gameService.create(ALICE, "NBA", 5, HistoricalSyntheticSeason.S).id();
        long drafting = gameService.create(ALICE, "NBA", 6, HistoricalSyntheticSeason.S).id();
        long abandoned = gameService.create(ALICE, "NBA", 7, HistoricalSyntheticSeason.S).id();
        gameService.abandon(abandoned, ALICE);
        gameService.auto(done, HistoricalGameService.Scope.FINISH, ALICE);

        JsonNode full = ok("/api/history/public/games/" + done);
        assertTrue(full.get("reducedUntil").isNull(), "free play has no day to wait for");
        assertEquals(168, full.get("state").get("picks").size());
        assertNoOwner(full.toString());
        // drafting, abandoned and missing are one and the same answer
        for (long id : new long[]{drafting, abandoned, 2_000_000_000L}) {
            MvcResult r = call("/api/history/public/games/" + id);
            assertEquals(404, r.getResponse().getStatus(), "game " + id);
            assertEquals("", r.getResponse().getContentAsString());
        }
    }

    // ---- the share preview (spec 079) ---------------------------------------------------------------------

    @Test
    void thePreviewOfTodaysReducedDailyHoldsNoPlayerAndMatchesTheFullGameAfterMidnight() throws Exception {
        LocalDate today = LocalDate.now(HistoricalDailyService.ZONE);
        long id = finishedDaily(today.minusDays(1));
        // every player name of the full game, read while it is full
        List<String> names = new ArrayList<>();
        for (JsonNode pick : ok("/api/history/public/games/" + id).get("state").get("picks")) {
            names.add(pick.get("player").get("name").asText());
        }
        assertTrue(names.size() > 100);
        JsonNode fullPreview = ok("/api/history/public/games/" + id + "/preview");

        jdbc.update("update historical_game set daily_date = ? where id = ?", today, id);
        MvcResult r = call("/api/history/public/games/" + id + "/preview");
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("public, max-age=300", r.getResponse().getHeader("Cache-Control"));
        String body = r.getResponse().getContentAsString();
        for (String n : names) assertFalse(body.contains(n), "a player name reached the preview: " + n);
        assertNoOwner(body);
        JsonNode reduced = json.readTree(body);
        // spec 078 on main: the title carries the day's number ("Redraft daily #8 · Nov 21 · ...")
        String wantHead = "Redraft daily #" + com.ballknowers.draftsim.historical.DailyNumber.of(today) + " · ";
        assertTrue(reduced.get("title").asText().startsWith(wantHead), reduced.get("title").asText());
        assertEquals(fullPreview.get("card").get("marks"), reduced.get("card").get("marks"),
                "the squares are the same whether the game is reduced or full");
        assertEquals(fullPreview.get("card").get("draftScore"), reduced.get("card").get("draftScore"));
        assertEquals(fullPreview.get("card").get("record"), reduced.get("card").get("record"));
        assertEquals(fullPreview.get("card").get("headline"), reduced.get("card").get("headline"));
    }

    @Test
    void thePreviewPngIsAPngAndUnknownOrUnfinishedGamesAre404() throws Exception {
        long done = gameService.create(ALICE, "NBA", 5, HistoricalSyntheticSeason.S).id();
        long drafting = gameService.create(ALICE, "NBA", 6, HistoricalSyntheticSeason.S).id();
        gameService.auto(done, HistoricalGameService.Scope.FINISH, ALICE);

        MvcResult png = call("/api/history/public/games/" + done + "/preview.png");
        assertEquals(200, png.getResponse().getStatus());
        assertEquals("image/png", png.getResponse().getContentType());
        assertEquals("public, max-age=86400", png.getResponse().getHeader("Cache-Control"));
        byte[] bytes = png.getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 1000 && (bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'));

        for (String suffix : new String[]{"/preview", "/preview.png"}) {
            for (long id : new long[]{drafting, 2_000_000_000L}) {
                MvcResult r = call("/api/history/public/games/" + id + suffix);
                assertEquals(404, r.getResponse().getStatus(), id + suffix);
            }
        }
    }

    private static void assertNoOwner(String body) {
        assertFalse(body.contains(ALICE), "the owner's id is in the response");
        assertFalse(body.contains("guest:"));
        assertFalse(body.toLowerCase().contains("sleeperuserid") || body.toLowerCase().contains("owner"));
    }
}
