package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.config.OwnerProperties;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Spec 076 T013: {@code GET /api/admin/usage} against real Postgres. Seeded rows sit in January 1980 (and one
 * rejected row in December 1979), days no real traffic or other test can use; they are deleted before and after.
 * The database is shared, so nothing here asserts that the whole table is empty.
 *
 * <p>SKIPS when Postgres is not reachable, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUsageMvcIT {

    private static final String LEAGUE = "it-usage-admin-league";
    private static final String RANGE = "from=1980-01-01&to=1980-01-31";

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
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping admin usage IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OwnerProperties owner;

    @BeforeEach
    void fresh() {
        cleanup(jdbc);
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
        j.update("delete from usage_daily where day between date '1979-12-01' and date '1980-01-31'");
        j.update("delete from league where sleeper_id = ?", LEAGUE);
    }

    private void seed(String day, String site, String kind, String route, String name, String props,
                      String league, String sport, long count) {
        jdbc.update("insert into usage_daily (day, site, kind, route, name, props, league_id, sport, count)"
                + " values (cast(? as date), ?, ?, ?, ?, ?, ?, ?, ?)", day, site, kind, route, name, props, league, sport, count);
    }

    private MvcResult asAdmin(String query) throws Exception {
        return mvc.perform(get("/api/admin/usage" + (query.isEmpty() ? "" : "?" + query))
                .header("X-Admin-Token", TestAdmin.TOKEN)).andReturn();
    }

    private JsonNode ok(String query) throws Exception {
        MvcResult r = asAdmin(query);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void withoutTheTokenItIsRefused() throws Exception {
        for (String token : new String[]{null, "", "  ", "not-the-token"}) {
            var b = get("/api/admin/usage");
            if (token != null) b.header("X-Admin-Token", token);
            MvcResult r = mvc.perform(b).andReturn();
            assertEquals(403, r.getResponse().getStatus(), "token: " + token);
            assertTrue(r.getResponse().getContentAsString().contains("admin_token_required"));
        }
    }

    @Test
    void defaultsAreTodayAndThirtyDaysInEastern() throws Exception {
        JsonNode r = ok("");
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        assertEquals("America/New_York", r.get("timezone").asText());
        assertEquals(today.toString(), r.get("to").asText());
        assertEquals(today.minusDays(29).toString(), r.get("from").asText());
        assertEquals(owner.configured(), r.get("ownerConfigured").asBoolean());
        for (String list : new String[]{"viewsByDay", "viewsByRoute", "leagueViews", "events", "funnel"}) {
            assertTrue(r.get(list).isArray(), list);
        }
        assertTrue(r.has("countingSince") && r.has("gamesSince") && r.has("rejected"));
    }

    @Test
    void badRangesAnswer400WithTheContractCode() throws Exception {
        for (String q : new String[]{"from=nope", "to=2026-13-40", "from=1980-02-01&to=1980-01-01",
                "from=1980-01-01&to=1981-01-01", "from=2026-1-1"}) {
            MvcResult r = asAdmin(q);
            assertEquals(400, r.getResponse().getStatus(), q);
            JsonNode body = json.readTree(r.getResponse().getContentAsString());
            assertEquals("bad_range", body.get("code").asText(), q);
            assertTrue(body.get("message").isTextual());
        }
        // 366 days inclusive is the most allowed
        assertEquals(200, asAdmin("from=1980-01-01&to=1980-12-31").getResponse().getStatus());
        assertEquals(400, asAdmin("from=1980-01-01&to=1981-01-01").getResponse().getStatus());
    }

    @Test
    void anEmptyRangeGivesEmptyListsAndZeroRejected() throws Exception {
        JsonNode r = ok(RANGE);
        assertEquals(0, r.get("viewsByDay").size());
        assertEquals(0, r.get("viewsByRoute").size());
        assertEquals(0, r.get("leagueViews").size());
        assertEquals(0, r.get("events").size());
        assertEquals(0, r.get("funnel").size());
        assertEquals(0, r.get("rejected").asInt());
    }

    @Test
    void seededRowsComeBackInEverySection() throws Exception {
        jdbc.update("insert into league (sport, season, sleeper_id, name) values ('nba', 2026, ?, 'IT admin usage league')", LEAGUE);
        seed("1980-01-05", "timemachine", "view", "/a", "", "", "", "", 3);
        seed("1980-01-06", "timemachine", "view", "/a", "", "", "", "", 4);
        seed("1980-01-06", "leagues", "view", "/b", "", "", "", "", 9);
        seed("1980-01-06", "leagues", "league-view", "/b", "", "", LEAGUE, "nba", 5);
        seed("1980-01-06", "leagues", "league-view", "/b", "", "", "it-usage-admin-gone", "nfl", 2);
        seed("1980-01-06", "timemachine", "event", "", "result-shared", "daily=no;sport=nba;who=guest", "", "", 6);
        seed("1980-01-06", "timemachine", "rejected", "", "", "", "", "", 7);

        JsonNode r = ok(RANGE);
        assertEquals(2, r.get("countingSince").size());
        assertEquals("leagues", r.get("countingSince").get(0).get("site").asText());
        assertEquals("1980-01-06", r.get("countingSince").get(0).get("since").asText(), "each site has its own first day");
        assertEquals("timemachine", r.get("countingSince").get(1).get("site").asText());
        assertEquals("1980-01-05", r.get("countingSince").get(1).get("since").asText());

        JsonNode days = r.get("viewsByDay");
        assertEquals(3, days.size());
        assertEquals("1980-01-05", days.get(0).get("day").asText());
        assertEquals(3, days.get(0).get("views").asInt());

        JsonNode routes = r.get("viewsByRoute");
        assertEquals("leagues", routes.get(0).get("site").asText(), "most views first");
        assertEquals(9, routes.get(0).get("views").asInt());
        assertEquals(7, routes.get(1).get("views").asInt(), "3 + 4 summed over the range");

        JsonNode leagues = r.get("leagueViews");
        assertEquals(2, leagues.size());
        assertEquals(LEAGUE, leagues.get(0).get("leagueId").asText());
        assertEquals("IT admin usage league", leagues.get(0).get("leagueName").asText());
        assertEquals("nba", leagues.get(0).get("sport").asText());
        assertTrue(leagues.get(1).get("leagueName").isNull(), "a league row gone since: null name, not an error");
        assertEquals("nfl", leagues.get(1).get("sport").asText());

        JsonNode ev = r.get("events");
        assertEquals(1, ev.size());
        assertEquals("result-shared", ev.get(0).get("name").asText());
        assertEquals("guest", ev.get(0).get("props").get("who").asText());
        assertEquals("nba", ev.get(0).get("props").get("sport").asText());
        assertEquals(6, ev.get(0).get("count").asInt());

        assertEquals(7, r.get("rejected").asInt());
    }

    @Test
    void countingSinceIgnoresRejectedRows() throws Exception {
        seed("1979-12-31", "timemachine", "rejected", "", "", "", "", "", 2);
        seed("1980-01-05", "timemachine", "view", "/a", "", "", "", "", 1);
        JsonNode r = ok(RANGE);
        assertEquals(1, r.get("countingSince").size());
        assertEquals("1980-01-05", r.get("countingSince").get(0).get("since").asText(), "a rejected row is not counting");
        // and a range that holds only the rejected row still reports it, with the views empty
        JsonNode early = ok("from=1979-12-01&to=1979-12-31");
        assertEquals(2, early.get("rejected").asInt());
        assertFalse(early.get("viewsByDay").size() > 0);
    }

    @Test
    void rangeEdgesAreInclusive() throws Exception {
        seed("1980-01-01", "timemachine", "view", "/a", "", "", "", "", 1);
        seed("1980-01-31", "timemachine", "view", "/a", "", "", "", "", 1);
        seed("1979-12-31", "timemachine", "view", "/a", "", "", "", "", 5);
        assertEquals(2, ok(RANGE).get("viewsByDay").size());
    }
}
