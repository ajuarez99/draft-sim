package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueWeeklyPotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 048 T014/T018: the real HTTP surface of the weekly pot -- GET and the commissioner's PUT -- through the
 * app's own ObjectMapper, the real membership scoping, the platform guard and Postgres.
 *
 * <p>Seeds throwaway season-2094 rows (ids prefixed {@code it048-}) and deletes them. Every seeded league is
 * {@code status = complete} so the live read ({@code LiveWeekSource.peek}) never goes to Sleeper's network.
 * SKIPS when Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyPotControllerIT {

    private static final String NFL = "it048-nfl";
    private static final String NBA = "it048-nba";
    private static final String ESPN = "it048-espn";
    private static final String ME = "it048-me";          // roster 1, the commissioner
    private static final String MEMBER = "it048-member";  // roster 2, an ordinary member
    private static final String STRANGER = "it048-stranger";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping weekly-pot IT");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private LeagueWeeklyPotRepository settings;
    @Autowired private ObjectMapper json;

    private long nflId;
    private long nbaId;
    private long meMgr;

    @BeforeEach
    void setUp() {
        tearDown();
        meMgr = manager(ME, "Me Manager");
        long memberMgr = manager(MEMBER, "Member Manager");
        long m3 = manager("it048-m3", "Third");
        long m4 = manager("it048-m4", "Fourth");
        manager(STRANGER, "Stranger");

        // NFL: 4 rosters, playoffs start in week 4 so the regular season is weeks 1..3.
        nflId = league(NFL, "nfl", "SLEEPER", 4, "{\"playoff_week_start\": 4}");
        leagueMembers.upsert(nflId, meMgr, true, "Team Me");
        leagueMembers.upsert(nflId, memberMgr, false, "Team Member");
        leagueMembers.upsert(nflId, m3, false, null);
        leagueMembers.upsert(nflId, m4, false, null);
        roster(nflId, 1, meMgr);
        roster(nflId, 2, memberMgr);
        roster(nflId, 3, m3);
        roster(nflId, 4, m4);
        // week 1: roster 1 wins; week 2: roster 2 wins; week 3: rosters 3 and 4 tie; week 4 is a playoff week.
        week(nflId, 1, 100, 90, 80, 70);
        week(nflId, 2, 105, 110, 80, 70);
        week(nflId, 3, 60, 50, 120, 120);
        week(nflId, 4, 10, 10, 10, 999);
        jdbc.update("insert into league_weekly_pot (league_id, enabled, stake_cents) values (?, true, 100)", nflId);

        // NBA: no playoff format recorded, roster 2 has no manager, week 2 was never stored.
        nbaId = league(NBA, "nba", "SLEEPER", 2, "{}");
        leagueMembers.upsert(nbaId, meMgr, true, "Team Me");
        roster(nbaId, 1, meMgr);
        roster(nbaId, 2, null);
        week(nbaId, 1, 200, 150);
        week(nbaId, 3, 180, 150);
        jdbc.update("insert into league_weekly_pot (league_id, enabled, stake_cents) values (?, true, 100)", nbaId);

        long espn = league(ESPN, "nfl", "ESPN", 2, "{}");
        leagueMembers.upsert(espn, meMgr, true, "Team Me");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id in (?, ?, ?)", NFL, NBA, ESPN);
        jdbc.update("delete from manager where sleeper_user_id like 'it048-%'");
    }

    private long manager(String sleeperUserId, String name) {
        return jdbc.queryForObject("insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, name);
    }

    private long league(String sleeperId, String sport, String platform, int rosters, String settingsJson) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, platform, status, settings_json)
                values (?, 2094, ?, 'IT pot', ?, ?, 'complete', ?::jsonb) returning id""",
                Long.class, sport, sleeperId, rosters, platform, settingsJson);
    }

    private void roster(long leagueId, int rosterId, Long managerId) {
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id) values (?, ?, ?)",
                leagueId, managerId, rosterId);
    }

    /** Roster i (1-based) scores {@code points[i-1]} in {@code week}. */
    private void week(long leagueId, int week, double... points) {
        for (int i = 0; i < points.length; i++) {
            jdbc.update("""
                    insert into roster_week_points (league_id, season, week, roster_id, starters_points)
                    values (?, 2094, ?, ?, ?)""", leagueId, week, i + 1, points[i]);
        }
    }

    private MockHttpServletRequestBuilder getAs(String league, String user) {
        return get("/api/leagues/" + league + "/weekly-pot").header("X-Sleeper-User", user);
    }

    private MockHttpServletRequestBuilder putAs(String league, String user, String body) {
        return put("/api/leagues/" + league + "/weekly-pot/settings").header("X-Sleeper-User", user)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode body(MockHttpServletRequestBuilder req) throws Exception {
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static Map<Integer, JsonNode> balancesByRoster(JsonNode root) {
        Map<Integer, JsonNode> out = new HashMap<>();
        root.get("balances").forEach(b -> out.put(b.get("rosterId").asInt(), b));
        return out;
    }

    /** Applies every debt in {@code list} to the balances; everyone must end at zero. */
    private static void assertClears(JsonNode root, String list) {
        Map<Integer, Long> net = new HashMap<>();
        root.get("balances").forEach(b -> net.put(b.get("rosterId").asInt(), b.get("netCents").asLong()));
        root.get(list).forEach(d -> {
            net.merge(d.get("fromRosterId").asInt(), d.get("cents").asLong(), Long::sum);
            net.merge(d.get("toRosterId").asInt(), -d.get("cents").asLong(), Long::sum);
        });
        net.forEach((id, v) -> assertEquals(0L, v, list + " leaves roster " + id + " uncleared"));
    }

    @Test
    void aStrangerOrNoIdentityIs404() throws Exception {
        mvc.perform(get("/api/leagues/" + NFL + "/weekly-pot")).andExpect(status().isNotFound());
        mvc.perform(getAs(NFL, STRANGER)).andExpect(status().isNotFound());
        mvc.perform(getAs("no-such-league", ME)).andExpect(status().isNotFound());
        mvc.perform(putAs(NFL, STRANGER, "{\"enabled\":true,\"stakeCents\":100}")).andExpect(status().isNotFound());
    }

    @Test
    void theOnPayloadHasTheContractShapeAndTheArithmeticAddsUp() throws Exception {
        JsonNode root = body(getAs(NFL, ME));
        assertEquals(2094, root.get("season").asInt());
        assertEquals("nfl", root.get("sport").asText());
        assertTrue(root.get("enabled").asBoolean());
        assertEquals(100, root.get("stakeCents").asInt());
        assertTrue(root.get("canEdit").asBoolean());
        assertTrue(root.get("commissionerKnown").asBoolean());
        assertTrue(root.get("regularSeasonKnown").asBoolean());
        assertEquals(3, root.get("regularSeasonThroughWeek").asInt());
        assertEquals(3, root.get("throughWeek").asInt());
        assertTrue(root.get("seasonFinal").asBoolean());
        assertTrue(root.get("tieCentRule").asText().contains("house convention"));
        // legitimately-null fields are present as explicit nulls, not dropped
        assertTrue(root.has("liveLeader") && root.get("liveLeader").isNull());
        assertFalse(root.get("liveUnavailable").asBoolean());

        // the playoff week (4) is not in the pot: only weeks 1..3
        JsonNode weeks = root.get("weeks");
        assertEquals(3, weeks.size());
        assertEquals(1, weeks.get(0).get("winners").get(0).asInt());
        assertEquals("DECIDED", weeks.get(0).get("status").asText());
        assertEquals(300L, weeks.get(0).get("potCents").asLong());
        assertEquals(3, weeks.get(0).get("payers").asInt());
        assertEquals(100.0, weeks.get(0).get("winningPoints").asDouble());
        assertEquals(90.0, weeks.get(0).get("runnerUpPoints").asDouble());
        assertEquals(2, weeks.get(1).get("winners").get(0).asInt());
        // the tie week: both co-winners, 100c split evenly from the two payers
        assertEquals(2, weeks.get(2).get("winners").size());
        assertEquals(3, weeks.get(2).get("winners").get(0).asInt());
        assertEquals(4, weeks.get(2).get("winners").get(1).asInt());
        assertEquals(200L, weeks.get(2).get("potCents").asLong());

        Map<Integer, JsonNode> by = balancesByRoster(root);
        assertEquals(4, by.size());
        assertEquals(100L, by.get(1).get("netCents").asLong());
        assertEquals(100L, by.get(2).get("netCents").asLong());
        assertEquals(-100L, by.get(3).get("netCents").asLong());
        assertEquals(-100L, by.get(4).get("netCents").asLong());
        assertEquals(1, by.get(3).get("weeksWon").asInt());
        assertEquals(100L, by.get(3).get("collectedCents").asLong());
        assertEquals(200L, by.get(3).get("paidCents").asLong());
        long sum = by.values().stream().mapToLong(b -> b.get("netCents").asLong()).sum();
        assertEquals(0L, sum);

        // names, the viewer flag and the explicit-null avatar come from RosterOwners, not username matching
        assertEquals("Team Me", by.get(1).get("name").asText());
        assertTrue(by.get(1).get("isMe").asBoolean());
        assertFalse(by.get(2).get("isMe").asBoolean());
        assertEquals("Third", by.get(3).get("name").asText());
        assertTrue(by.get(1).has("avatarId") && by.get(1).get("avatarId").isNull());
        assertFalse(by.get(1).get("noManager").asBoolean());
        // ordered by net desc, weeks won desc, roster asc
        assertEquals(1, root.get("balances").get(0).get("rosterId").asInt());
        assertEquals(4, root.get("balances").get(3).get("rosterId").asInt());

        assertClears(root, "pairwise");
        assertClears(root, "shortcut");
        assertTrue(root.get("shortcut").size() <= root.get("pairwise").size());
        assertEquals(2, root.get("shortcut").size());
    }

    @Test
    void anNbaLeagueWithNoPlayoffFormatAndAMissingWeekStillSettlesCorrectly() throws Exception {
        JsonNode root = body(getAs(NBA, ME));
        assertEquals("nba", root.get("sport").asText());
        assertFalse(root.get("regularSeasonKnown").asBoolean());
        assertTrue(root.has("regularSeasonThroughWeek") && root.get("regularSeasonThroughWeek").isNull());
        JsonNode weeks = root.get("weeks");
        assertEquals(3, weeks.size());
        assertEquals("DECIDED", weeks.get(0).get("status").asText());
        assertEquals(1, weeks.get(0).get("winners").get(0).asInt());
        // week 2 was never stored: listed as MISSING, no winner, explicit nulls, and it stops throughWeek
        assertEquals("MISSING", weeks.get(1).get("status").asText());
        assertEquals(0, weeks.get(1).get("winners").size());
        assertTrue(weeks.get(1).has("winningPoints") && weeks.get(1).get("winningPoints").isNull());
        assertTrue(weeks.get(1).has("runnerUpPoints") && weeks.get(1).get("runnerUpPoints").isNull());
        assertEquals(1, root.get("throughWeek").asInt());
        assertEquals(1, weeks.get(2).get("winners").get(0).asInt());

        Map<Integer, JsonNode> by = balancesByRoster(root);
        assertEquals(200L, by.get(1).get("netCents").asLong());
        assertEquals(-200L, by.get(2).get("netCents").asLong());
        // roster 2 has no manager: explicit null managerId, flagged, still named
        assertTrue(by.get(2).has("managerId") && by.get(2).get("managerId").isNull());
        assertTrue(by.get(2).get("noManager").asBoolean());
        assertEquals("Roster 2", by.get(2).get("name").asText());
        assertEquals(1, root.get("shortcut").size());
        assertClears(root, "shortcut");
        assertClears(root, "pairwise");
    }

    @Test
    void theOffStateCarriesNoMoneyFieldsAndAStrangersViewHasNoEditRights() throws Exception {
        jdbc.update("update league_weekly_pot set enabled = false where league_id = ?", nflId);
        JsonNode off = body(getAs(NFL, MEMBER));
        assertFalse(off.get("enabled").asBoolean());
        assertFalse(off.get("canEdit").asBoolean());
        assertTrue(off.get("commissionerKnown").asBoolean());
        assertEquals(100, off.get("stakeCents").asInt());
        for (String key : new String[]{"balances", "weeks", "pairwise", "shortcut", "liveLeader", "tieCentRule"}) {
            assertFalse(off.has(key), "off body must not carry " + key);
        }
        // a league that never had a row at all reads the same way
        jdbc.update("delete from league_weekly_pot where league_id = ?", nflId);
        JsonNode none = body(getAs(NFL, ME));
        assertFalse(none.get("enabled").asBoolean());
        assertTrue(none.get("canEdit").asBoolean());
        assertFalse(none.has("balances"));
    }

    @Test
    void anEspnLeagueIsRefusedByThePlatformGuardOnBothRoutes() throws Exception {
        mvc.perform(getAs(ESPN, ME))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLATFORM_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.message").value("The weekly pot follows Sleeper leagues only."));
        mvc.perform(putAs(ESPN, ME, "{\"enabled\":true,\"stakeCents\":100}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLATFORM_NOT_SUPPORTED"));
    }

    // ------------------------------------------------------------ the commissioner's switch (T018)

    @Test
    void aNonCommissionerGets403WithCommissionerKnown() throws Exception {
        mvc.perform(putAs(NFL, MEMBER, "{\"enabled\":false,\"stakeCents\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.commissionerKnown").value(true))
                .andExpect(jsonPath("$.message").value("only this league's Sleeper commissioner may change the weekly pot"));
        // nothing was written
        assertTrue(settings.find(nflId).orElseThrow().enabled());
    }

    @Test
    void whenNoCommissionerIsRecordedTheRefusalSaysSo() throws Exception {
        jdbc.update("update league_member set is_commissioner = false where league_id = ?", nflId);
        mvc.perform(putAs(NFL, MEMBER, "{\"enabled\":true,\"stakeCents\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.commissionerKnown").value(false))
                .andExpect(jsonPath("$.message").value("No commissioner is recorded for this league yet."));
    }

    @Test
    void badBodiesAre400BeforeAnythingIsWritten() throws Exception {
        for (String bad : new String[]{
                "{\"stakeCents\":100}",                       // enabled missing
                "{\"enabled\":true}",                         // stakeCents missing
                "{\"enabled\":true,\"stakeCents\":0}",
                "{\"enabled\":true,\"stakeCents\":100001}",
                "{\"enabled\":true,\"stakeCents\":-5}",
                "{\"enabled\":true,\"stakeCents\":150.7}",    // would silently coerce to 150
                "{\"enabled\":true,\"stakeCents\":100.0}",
                "{\"enabled\":true,\"stakeCents\":\"100\"}",
                "{\"enabled\":\"yes\",\"stakeCents\":100}",
                "{\"enabled\":true,\"stakeCents\":99999999999}"}) {
            mvc.perform(putAs(NFL, ME, bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").exists());
        }
        mvc.perform(put("/api/leagues/" + NFL + "/weekly-pot/settings").header("X-Sleeper-User", ME)
                .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        assertEquals(100, settings.find(nflId).orElseThrow().stakeCents());
    }

    @Test
    void theCommissionerCanRestakeTurnOffAndBackOn() throws Exception {
        JsonNode at100 = body(getAs(NFL, ME));
        JsonNode doubled = body(putAs(NFL, ME, "{\"enabled\":true,\"stakeCents\":200}"));
        assertEquals(200, doubled.get("stakeCents").asInt());
        // untied weeks double exactly; the tie week is 200c over two winners, which divides evenly here
        assertEquals(2 * at100.get("weeks").get(0).get("potCents").asLong(), doubled.get("weeks").get(0).get("potCents").asLong());
        assertEquals(2 * at100.get("weeks").get(1).get("potCents").asLong(), doubled.get("weeks").get(1).get("potCents").asLong());
        Map<Integer, JsonNode> was = balancesByRoster(at100);
        Map<Integer, JsonNode> now = balancesByRoster(doubled);
        for (int r = 1; r <= 4; r++) {
            assertEquals(2 * was.get(r).get("netCents").asLong(), now.get(r).get("netCents").asLong(), "roster " + r);
        }
        assertClears(doubled, "shortcut");
        assertClears(doubled, "pairwise");
        assertEquals(meMgr, jdbc.queryForObject("select updated_by from league_weekly_pot where league_id = ?", Long.class, nflId));

        JsonNode off = body(putAs(NFL, ME, "{\"enabled\":false,\"stakeCents\":200}"));
        assertFalse(off.get("enabled").asBoolean());
        assertEquals(200, off.get("stakeCents").asInt());
        assertFalse(off.has("balances"));
        JsonNode readBack = body(getAs(NFL, ME));
        assertFalse(readBack.get("enabled").asBoolean());
        assertFalse(readBack.has("weeks"));

        JsonNode back = body(putAs(NFL, ME, "{\"enabled\":true,\"stakeCents\":200}"));
        assertTrue(back.get("enabled").asBoolean());
        assertEquals(200, back.get("stakeCents").asInt());
        assertEquals(3, back.get("weeks").size());
    }

    @Test
    void anOddStakeFollowsTheTieCentRuleAndStillSumsToZero() throws Exception {
        // 101c over two co-winners: 51/50 per payer, the extra cent to the lower roster id (3)
        JsonNode odd = body(putAs(NFL, ME, "{\"enabled\":true,\"stakeCents\":101}"));
        Map<Integer, JsonNode> by = balancesByRoster(odd);
        assertEquals(102L, by.get(3).get("collectedCents").asLong());
        assertEquals(100L, by.get(4).get("collectedCents").asLong());
        long sum = by.values().stream().mapToLong(b -> b.get("netCents").asLong()).sum();
        assertEquals(0L, sum);
        assertClears(odd, "shortcut");
        assertClears(odd, "pairwise");
    }

    @Test
    void aNullUpdatedByBindsAndStoresAsNull() {
        // the configured app owner has no manager row, so updated_by is null (lessons bug class 3)
        settings.upsert(nbaId, true, 250, null);
        assertEquals(250, settings.find(nbaId).orElseThrow().stakeCents());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from league_weekly_pot where league_id = ? and updated_by is null", Integer.class, nbaId));
    }
}
