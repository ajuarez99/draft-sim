package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.usage.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spec 076 T010: the public collection route against real Postgres. The clock is a settable bean fixed in 1990
 * (late on 12 March in New York), so every row this test writes lands on a day no real traffic can share, and
 * every row on that day is deleted before and after. The local database is shared with other sessions.
 *
 * <p>SKIPS when Postgres is not reachable, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UsageCollectMvcIT {

    static final String DAY = "1990-03-12";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    // 22:40 on 12 March in New York. Every test runs a few minutes after this, so the clock only moves forward
    // between tests and the service's once-a-minute flush timer (which a test can leave set) is never ahead of it.
    private static final Instant NOON = Instant.parse("1990-03-13T03:40:00Z");
    private static final String BROWSER = "Mozilla/5.0 (X11; Linux x86_64) Firefox/120.0";
    private static final String LEAGUE = "it-usage-league-1";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MutableClock usageTestClock() {
            return new MutableClock(NOON, ET);
        }
    }

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
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping usage collect IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private com.ballknowers.draftsim.usage.UsageService usageService;

    private final AtomicInteger ipSeq = new AtomicInteger();
    private static int window;

    @BeforeEach
    void fresh() {
        cleanup(jdbc);
        // a new limiter window for every test, still on the same Eastern day
        clock.set(NOON.plus(Duration.ofMinutes(++window)));
        jdbc.update("insert into league (sport, season, sleeper_id, name) values ('nfl', 2026, ?, 'IT usage league')", LEAGUE);
    }

    @AfterAll
    static void removeEverything() {
        try {
            cleanup(new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url(), "draftsim", "draftsim")));
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was written either.
        }
    }

    private static void cleanup(JdbcTemplate j) {
        j.update("delete from usage_daily where day = date '" + DAY + "'");
        j.update("delete from league where sleeper_id like 'it-usage-league-%'");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private MvcResult send(String body, String userAgent) throws Exception {
        return sendFrom("10.76.0." + ipSeq.incrementAndGet(), body, userAgent);
    }

    private MvcResult sendFrom(String ip, String body, String userAgent) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/usage").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", ip).content(body);
        if (userAgent != null) b.header("User-Agent", userAgent);
        return mvc.perform(b).andReturn();
    }

    private void assert204(MvcResult r) throws Exception {
        assertEquals(204, r.getResponse().getStatus());
        assertEquals("", r.getResponse().getContentAsString(), "an empty body, always");
    }

    private long count(String where, Object... args) {
        Long n = jdbc.queryForObject("select coalesce(sum(count), 0) from usage_daily where day = date '" + DAY
                + "' and " + where, Long.class, args);
        return n == null ? 0 : n;
    }

    private long rows(String where) {
        Long n = jdbc.queryForObject("select count(*) from usage_daily where day = date '" + DAY + "' and " + where, Long.class);
        return n == null ? 0 : n;
    }

    private static String viewBody(String route) {
        return "{\"kind\":\"view\",\"site\":\"timemachine\",\"route\":\"" + route + "\"}";
    }

    // ---- the tests ----------------------------------------------------------------------------------------

    @Test
    void aValidViewAddsOneAndASecondAddsToTheSameRow() throws Exception {
        String route = firstRoute("timemachine");
        assert204(send(viewBody(route), BROWSER));
        assertEquals(1, count("kind = 'view' and site = 'timemachine' and route = ?", route));
        assert204(send(viewBody(route), BROWSER));
        assertEquals(2, count("kind = 'view' and site = 'timemachine' and route = ?", route));
        assertEquals(1, rows("kind = 'view'"), "the upsert reused the row");
    }

    @Test
    void aLeagueViewStoresTheTablesSportAndAnUnknownLeagueStoresNothing() throws Exception {
        String route = firstRoute("leagues");
        assert204(send("{\"kind\":\"league-view\",\"site\":\"leagues\",\"route\":\"" + route + "\",\"league\":\"" + LEAGUE + "\"}", BROWSER));
        Map<String, Object> row = jdbc.queryForMap("select * from usage_daily where kind = 'league-view' and day = date '" + DAY + "'");
        assertEquals(LEAGUE, row.get("league_id"));
        assertEquals("nfl", row.get("sport"), "the sport comes from the league table");
        assertEquals(1L, row.get("count"));

        assert204(send("{\"kind\":\"league-view\",\"site\":\"leagues\",\"route\":\"" + route + "\",\"league\":\"it-usage-league-missing\"}", BROWSER));
        assertEquals(1, rows("kind = 'league-view'"), "no row for a league that does not exist");
    }

    @Test
    void anEventIsStoredWithCanonicalPropsBuiltFromTheVocabulary() throws Exception {
        assert204(send("{\"kind\":\"event\",\"site\":\"timemachine\",\"name\":\"result-shared\","
                + "\"props\":{\"who\":\"member\",\"sport\":\"nfl\",\"daily\":\"no\"}}", BROWSER));
        Map<String, Object> row = jdbc.queryForMap("select * from usage_daily where kind = 'event' and day = date '" + DAY + "'");
        assertEquals("result-shared", row.get("name"));
        assertEquals("daily=no;sport=nfl;who=member", row.get("props"));
        assertEquals("", row.get("route"));
        assertEquals("", row.get("league_id"));
    }

    @Test
    void junkAnswers204AndAddsNoViewOrEventRows() throws Exception {
        String route = firstRoute("timemachine");
        String[] junk = {
                "{\"site\":\"timemachine\",\"route\":\"/definitely/not/a/route\"}",
                "{\"site\":\"timemachine\",\"route\":\"" + route + "\",\"extra\":\"field\"}",
                "{\"site\":\"timemachine\",\"name\":\"nope\",\"props\":{}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{\"via\":\"carrier-pigeon\"}}",
                "{\"site\":\"timemachine\",\"name\":\"league-added\",\"props\":{\"via\":\"espn\",\"who\":\"me\"}}",
                "{\"site\":\"not-a-site\",\"route\":\"/\"}",
                "{\"route\":\"/\"}",
                "not json at all",
                "[1,2,3]",
                "",
                "{\"site\":\"timemachine\",\"route\":\"" + "x".repeat(3000) + "\"}",       // oversize
        };
        for (String body : junk) assert204(send(body, BROWSER));
        assertEquals(0, rows("kind in ('view', 'league-view', 'event')"), "junk is never counted as usage");
        // the oversize body must not have been read as valid either, even with a valid prefix
        assert204(send(viewBody(route) + " ".repeat(2000), BROWSER));
        assertEquals(0, rows("kind = 'view'"));
    }

    @Test
    void aCrawlerAndANonJsonContentTypeAreHandled() throws Exception {
        String route = firstRoute("timemachine");
        assert204(send(viewBody(route), "Mozilla/5.0 (compatible; Googlebot/2.1)"));
        assertEquals(0, rows("kind = 'view'"));
        // a text/plain beacon body still counts: the body is read raw
        MvcResult r = mvc.perform(post("/api/usage").contentType(MediaType.TEXT_PLAIN)
                .header("X-Real-IP", "10.76.9.9").header("User-Agent", BROWSER).content(viewBody(route))).andReturn();
        assert204(r);
        assertEquals(1, rows("kind = 'view'"));
    }

    @Test
    void noIdentityHeaderIsNeededOrRead() throws Exception {
        String route = firstRoute("timemachine");
        // none of the identity headers: counted
        assert204(send(viewBody(route), BROWSER));
        // every identity header, all of them garbage: also fine, and nothing of them is stored
        MvcResult r = mvc.perform(post("/api/usage").contentType(MediaType.APPLICATION_JSON)
                .header("X-Real-IP", "10.76.8.8").header("User-Agent", BROWSER)
                .header("X-Sleeper-User", "someone").header("X-Guest-Id", "guest-garbage")
                .header("X-League-Session", "nope").content(viewBody(route))).andReturn();
        assert204(r);
        assertEquals(2, count("kind = 'view'"));
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'usage_daily' order by ordinal_position", String.class);
        assertEquals(List.of("day", "site", "kind", "route", "name", "props", "league_id", "sport", "count"), columns,
                "the table holds only the data-model columns: no address, agent, hash, id or finer time");
        assertTrue(jdbc.queryForList("select route || name || props || league_id || sport from usage_daily where day = date '" + DAY + "'", String.class)
                .stream().noneMatch(s -> s.contains("someone") || s.contains("garbage") || s.contains("10.76")));
    }

    @Test
    void rejectedSendsTakeNoWriteAndAreFlushedAfterTheMinuteByTheNextAcceptedSend() throws Exception {
        String route = firstRoute("timemachine");
        assert204(send(viewBody(route), BROWSER));
        // a forced report() writes out any rejected counts other tests left in memory, so the next numbers are exact
        usageService.report(java.time.LocalDate.of(1990, 3, 12), java.time.LocalDate.of(1990, 3, 12));
        long before = count("kind = 'rejected' and site = 'timemachine'");
        for (int i = 0; i < 4; i++) assert204(send("{\"site\":\"timemachine\",\"route\":\"/nope\"}", BROWSER));
        assertEquals(before, count("kind = 'rejected' and site = 'timemachine'"), "a refused send writes nothing");
        clock.advance(Duration.ofSeconds(61));
        assert204(send(viewBody(route), BROWSER));
        assertEquals(before + 4, count("kind = 'rejected' and site = 'timemachine'"),
                "the next accepted send after the minute flushed them");
    }

    @Test
    void theDayBucketIsTheEasternDayNotTheUtcDay() throws Exception {
        // 03:40 UTC on the 13th is still the 12th in New York: counted under the 12th, not the 13th
        assertEquals("1990-03-12", java.time.LocalDate.ofInstant(clock.instant(), ET).toString());
        assert204(send(viewBody(firstRoute("timemachine")), BROWSER));
        assertEquals(1, rows("kind = 'view'"));
    }

    @Test
    void anOversizedRealIpHeaderIsNotAKeyItFallsBackToTheSocketAddress() throws Exception {
        String route = firstRoute("timemachine");
        // 70 sends, each with a different 5,000-character header: if the header were the key each would be
        // its own address and all 70 would count. Keyed on the one socket address, the limit of 60 applies.
        for (int i = 0; i < 70; i++) {
            assert204(sendFrom(("x".repeat(4990) + String.format("%010d", i)), viewBody(route), BROWSER));
        }
        assertEquals(60, count("kind = 'view'"));
        // a 45-character address (the longest IPv6 text) is still taken as given
        String v6 = "0000:0000:0000:0000:0000:ffff:255.255.255.255";
        assertEquals(45, v6.length());
        assert204(sendFrom(v6, viewBody(route), BROWSER));
        assertEquals(61, count("kind = 'view'"));
    }

    @Test
    void oneAddressIsLimitedToSixtyAWindowAndAnotherIsNot() throws Exception {
        String route = firstRoute("timemachine");
        for (int i = 0; i < 70; i++) assert204(sendFrom("10.77.0.1", viewBody(route), BROWSER));
        assertEquals(60, count("kind = 'view'"), "the 61st to 70th were refused, still with 204");
        assert204(sendFrom("10.77.0.2", viewBody(route), BROWSER));
        assertEquals(61, count("kind = 'view'"));
    }

    private String firstRoute(String site) {
        return switch (site) {
            case "timemachine" -> vocabularyRoute("timemachine");
            default -> vocabularyRoute(site);
        };
    }

    @Autowired private com.ballknowers.draftsim.usage.UsageVocabulary vocabulary;

    private String vocabularyRoute(String site) {
        return vocabulary.routes(site).stream().sorted().findFirst().orElseThrow();
    }
}
