package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.store.LeagueMemberRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 057 T012: the real HTTP surface of the commissioner's score entry -- GET, PUT, DELETE -- through the
 * app's own ObjectMapper, real membership scoping and Postgres. Seeds throwaway season-2093 rows (ids prefixed
 * {@code it057-}). SKIPS when Postgres is unreachable, so the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScoreOverrideControllerIT {

    private static final String NBA = "it057-nba";
    private static final String ME = "it057-me";          // roster 1, the commissioner
    private static final String MEMBER = "it057-member";  // roster 2, an ordinary member
    private static final String STRANGER = "it057-stranger";
    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @BeforeAll
    static void requiresPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private ObjectMapper json;

    private long leagueId;

    @BeforeEach
    void setUp() {
        tearDown();
        long me = manager(ME, "Me Manager");
        long member = manager(MEMBER, "Member Manager");
        long m3 = manager("it057-m3", "Third");
        long m4 = manager("it057-m4", "Fourth");
        manager(STRANGER, "Stranger");

        leagueId = jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, platform, status, settings_json)
                values ('nba', 2093, ?, 'IT 057', 4, 'SLEEPER', 'complete', '{}'::jsonb) returning id""",
                Long.class, NBA);
        leagueMembers.upsert(leagueId, me, true, "Team Me");
        leagueMembers.upsert(leagueId, member, false, "Team Member");
        leagueMembers.upsert(leagueId, m3, false, null);
        leagueMembers.upsert(leagueId, m4, false, null);
        roster(1, me, 1, 1);
        roster(2, member, 1, 1);
        roster(3, m3, 1, 1);
        roster(4, m4, 1, 1);
        // week 1: 1 v 2 and 3 v 4; the feed has roster 2 beating roster 1 (233 to 227). Week 2 is 1 v 3, 2 v 4.
        week(1, 227, 233, 150, 140);
        week(2, 200, 190, 180, 170);
        matchup(1, 1, 1); matchup(1, 2, 1); matchup(1, 3, 2); matchup(1, 4, 2);
        matchup(2, 1, 1); matchup(2, 3, 1); matchup(2, 2, 2); matchup(2, 4, 2);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", NBA);
        jdbc.update("delete from manager where sleeper_user_id like 'it057-%'");
    }

    private long manager(String sleeperUserId, String name) {
        return jdbc.queryForObject("insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, name);
    }

    private void roster(int rosterId, long managerId, int wins, int losses) {
        jdbc.update("insert into roster_season (league_id, manager_id, roster_id, wins, losses, ties) values (?, ?, ?, ?, ?, 0)",
                leagueId, managerId, rosterId, wins, losses);
    }

    private void week(int week, double... points) {
        for (int i = 0; i < points.length; i++) {
            jdbc.update("""
                    insert into roster_week_points (league_id, season, week, roster_id, starters_points)
                    values (?, 2093, ?, ?, ?)""", leagueId, week, i + 1, points[i]);
        }
    }

    private void matchup(int week, int roster, int matchupId) {
        jdbc.update("insert into league_matchup (league_id, season, week, roster_id, matchup_id) values (?, 2093, ?, ?, ?)",
                leagueId, week, roster, matchupId);
    }

    private int overrideRows() {
        return jdbc.queryForObject("select count(*) from week_score_override where league_id = ?", Integer.class, leagueId);
    }

    private static String path(int week) {
        return "/api/leagues/" + NBA + "/score-overrides/" + week;
    }

    private MockHttpServletRequestBuilder putAs(int week, String user, String body) {
        return put(path(week)).header("X-Sleeper-User", user).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode badRequest(MockHttpServletRequestBuilder req) throws Exception {
        return json.readTree(mvc.perform(req).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
    }

    private static String scores(String r1, String r2, String r3, String r4) {
        return "{\"scores\":[{\"rosterId\":1,\"points\":" + r1 + "},{\"rosterId\":2,\"points\":" + r2
                + "},{\"rosterId\":3,\"points\":" + r3 + "},{\"rosterId\":4,\"points\":" + r4 + "}]}";
    }

    private static double inUse(JsonNode view, int rosterId) {
        for (JsonNode t : view.get("teams")) if (t.get("rosterId").asInt() == rosterId) return t.get("inUsePoints").asDouble();
        throw new AssertionError("no team " + rosterId);
    }

    @Test
    void aStrangerOrNoIdentityIs404() throws Exception {
        mvc.perform(get(path(1))).andExpect(status().isNotFound());
        mvc.perform(get(path(1)).header("X-Sleeper-User", STRANGER)).andExpect(status().isNotFound());
        mvc.perform(putAs(1, STRANGER, scores("1", "2", "3", "4"))).andExpect(status().isNotFound());
        mvc.perform(delete(path(1)).header("X-Sleeper-User", STRANGER)).andExpect(status().isNotFound());
    }

    @Test
    void anyMemberCanReadTheViewButOnlyTheCommissionerCanEdit() throws Exception {
        JsonNode asMember = ok(get(path(1)).header("X-Sleeper-User", MEMBER));
        assertEquals(1, asMember.get("week").asInt());
        assertTrue(asMember.get("isFinal").asBoolean());
        assertFalse(asMember.get("canEdit").asBoolean());
        assertTrue(asMember.get("commissionerKnown").asBoolean());
        assertEquals("FEED", asMember.get("source").asText());
        assertTrue(asMember.get("enteredAt").isNull());
        assertEquals(4, asMember.get("teams").size());
        JsonNode t1 = asMember.get("teams").get(0);
        assertEquals(1, t1.get("rosterId").asInt());
        assertEquals("Team Me", t1.get("teamName").asText());
        assertEquals(227.0, t1.get("feedPoints").asDouble());
        assertEquals(227.0, t1.get("inUsePoints").asDouble());
        assertTrue(t1.get("overridePoints").isNull());
        assertEquals("UNAVAILABLE", asMember.get("checks").get("totals").get("status").asText(), "no snapshot yet");
        assertEquals("UNAVAILABLE", asMember.get("checks").get("record").get("status").asText());

        assertTrue(ok(get(path(1)).header("X-Sleeper-User", ME)).get("canEdit").asBoolean());
    }

    @Test
    void aNonCommissionerCannotSaveOrClearAndNothingChanges() throws Exception {
        mvc.perform(putAs(1, MEMBER, scores("239", "231", "150", "140"))).andExpect(status().isForbidden());
        assertEquals(0, overrideRows());

        ok(putAs(1, ME, scores("239", "231", "150", "140")));
        mvc.perform(delete(path(1)).header("X-Sleeper-User", MEMBER)).andExpect(status().isForbidden());
        assertEquals(4, overrideRows());
    }

    @Test
    void badBodiesAreRefusedAndNothingIsWritten() throws Exception {
        JsonNode partial = badRequest(putAs(1, ME,
                "{\"scores\":[{\"rosterId\":1,\"points\":239},{\"rosterId\":2,\"points\":231},{\"rosterId\":3,\"points\":150}]}"));
        assertTrue(partial.get("message").asText().contains("missing roster(s) [4]"), partial.toString());

        JsonNode extra = badRequest(putAs(1, ME,
                "{\"scores\":[{\"rosterId\":1,\"points\":239},{\"rosterId\":2,\"points\":231},{\"rosterId\":3,\"points\":150},"
                        + "{\"rosterId\":4,\"points\":140},{\"rosterId\":9,\"points\":1}]}"));
        assertTrue(extra.get("message").asText().contains("[9]"), extra.toString());

        assertTrue(badRequest(putAs(1, ME, scores("-1", "231", "150", "140"))).get("message").asText().contains("negative"));
        assertTrue(badRequest(putAs(1, ME, scores("239.005", "231", "150", "140"))).get("message").asText().contains("2 decimal"));
        assertTrue(badRequest(putAs(1, ME, scores("\"239\"", "231", "150", "140"))).get("message").asText().contains("number"));
        assertTrue(badRequest(putAs(1, ME, scores("null", "231", "150", "140"))).get("message").asText().contains("number"));
        assertTrue(badRequest(putAs(1, ME, scores("1e400", "231", "150", "140"))).get("message").asText().contains("points"));
        assertTrue(badRequest(putAs(1, ME, scores("1000000", "231", "150", "140"))).get("message").asText().contains("too large"));
        assertTrue(badRequest(putAs(1, ME, scores("1e999999999", "231", "150", "140"))).get("message").asText().contains("points"));
        badRequest(putAs(1, ME, "{}"));
        badRequest(putAs(1, ME, "{\"scores\":{}}"));
        badRequest(putAs(0, ME, scores("1", "2", "3", "4")));
        // week 5 was never stored, so it is not final
        assertTrue(badRequest(putAs(5, ME, scores("1", "2", "3", "4"))).get("message").asText().contains("not final"));
        assertEquals(0, overrideRows());
    }

    @Test
    void saveThenReadThenClearRoundTrip() throws Exception {
        JsonNode saved = ok(putAs(1, ME, scores("239.0", "231.5", "150", "140.25")));
        assertEquals("COMMISSIONER", saved.get("source").asText());
        assertNotNull(saved.get("enteredAt").asText());
        assertEquals(239.0, inUse(saved, 1));
        assertEquals(231.5, inUse(saved, 2));
        assertEquals(140.25, inUse(saved, 4));
        assertEquals(227.0, saved.get("teams").get(0).get("feedPoints").asDouble(), "the feed is still shown beside it");
        assertEquals(239.0, saved.get("teams").get(0).get("overridePoints").asDouble());
        assertEquals(4, overrideRows());
        assertEquals(jdbc.queryForObject("select id from manager where sleeper_user_id = ?", Long.class, ME),
                jdbc.queryForObject("select distinct entered_by from week_score_override where league_id = ?", Long.class, leagueId));

        // a second save replaces the week whole
        ok(putAs(1, ME, scores("100", "101", "102", "103")));
        assertEquals(4, overrideRows());
        JsonNode read = ok(get(path(1)).header("X-Sleeper-User", MEMBER));
        assertEquals("COMMISSIONER", read.get("source").asText());
        assertEquals(100.0, inUse(read, 1));
        // week 2 untouched
        assertEquals("FEED", ok(get(path(2)).header("X-Sleeper-User", MEMBER)).get("source").asText());

        JsonNode cleared = ok(delete(path(1)).header("X-Sleeper-User", ME));
        assertEquals("FEED", cleared.get("source").asText());
        assertEquals(227.0, inUse(cleared, 1));
        assertTrue(cleared.get("enteredAt").isNull());
        assertEquals(0, overrideRows());
    }

    /** Sleeper's official season totals through week 2 (week 1 as typed + the feed's week 2). Only leg 2, so the
     *  official tier cannot separate the weeks and the feed/override decide, as on NBA 2026. */
    private void snapshotThroughWeek2() {
        jdbc.update("""
                insert into roster_fpts_snapshot (league_id, roster_id, through_week, fpts, fpts_against)
                values (?, 1, 2, 439, 0), (?, 2, 2, 421, 0), (?, 3, 2, 330, 0), (?, 4, 2, 310, 0)""",
                leagueId, leagueId, leagueId, leagueId);
    }

    @Test
    void theTotalsCheckComparesInUseScoresWithSleepersSeasonTotal() throws Exception {
        snapshotThroughWeek2();
        // before any entry the feed's week 1 (227 / 233) does not add up to Sleeper's totals
        JsonNode before = ok(get(path(1)).header("X-Sleeper-User", ME));
        assertEquals("MISMATCH", before.get("checks").get("totals").get("status").asText());
        assertEquals(2, before.get("checks").get("totals").get("throughWeek").asInt());

        JsonNode typed = ok(putAs(1, ME, scores("239", "231", "150", "140")));
        JsonNode totals = typed.get("checks").get("totals");
        assertEquals("MATCH", totals.get("status").asText());
        assertEquals(4, totals.get("rows").size());
        assertEquals(439.0, totals.get("rows").get(0).get("inUseTotal").asDouble());
        assertEquals(439.0, totals.get("rows").get(0).get("sleeperTotal").asDouble());

        // a typo is shown with both numbers, and does not block the save
        JsonNode typo = ok(putAs(1, ME, scores("239", "213", "150", "140")));
        assertEquals("MISMATCH", typo.get("checks").get("totals").get("status").asText());
        assertEquals(403.0, typo.get("checks").get("totals").get("rows").get(1).get("inUseTotal").asDouble());
        assertEquals(421.0, typo.get("checks").get("totals").get("rows").get(1).get("sleeperTotal").asDouble());
        assertEquals(4, overrideRows());
    }

    @Test
    void theRecordCheckRecomputesWinsAndLossesFromTheScoresInUse() throws Exception {
        snapshotThroughWeek2();
        // Sleeper's official record after week 2: roster 1 won both, 2 and 3 split, 4 lost both
        jdbc.update("update roster_season set wins = 2, losses = 0 where league_id = ? and roster_id = 1", leagueId);
        jdbc.update("update roster_season set wins = 1, losses = 1 where league_id = ? and roster_id in (2, 3)", leagueId);
        jdbc.update("update roster_season set wins = 0, losses = 2 where league_id = ? and roster_id = 4", leagueId);

        // on the feed roster 2 beats roster 1 in week 1, so the recomputed record disagrees with Sleeper's
        JsonNode before = ok(get(path(1)).header("X-Sleeper-User", ME)).get("checks").get("record");
        assertEquals("MISMATCH", before.get("status").asText());
        assertEquals("1-1", before.get("rows").get(0).get("computed").asText());
        assertEquals("2-0", before.get("rows").get(0).get("sleeper").asText());

        JsonNode after = ok(putAs(1, ME, scores("239", "231", "150", "140"))).get("checks").get("record");
        assertEquals("MATCH", after.get("status").asText());
        assertEquals(2, after.get("throughWeek").asInt());
        assertEquals(4, after.get("rows").size());
    }
}
