package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.SleeperClient;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * claude/audit-2026-09-28/02-tendency-writes.md through the real HTTP stack: scoping of every
 * /api/managers/** route, notes only, notes private per author.
 *
 * <p>Uses the same {@code @MockitoBean} set as {@link AccessControlMvcIT} on purpose, so both share
 * one cached Spring context (claude/lessons.md #21). Postgres is real; SKIPS when unreachable, so
 * the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManagerControllerMvcIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    static final String LEAGUE = "it-mgr-league";
    static final String MEMBER = "it-mgr-member";
    static final String OTHER_MEMBER = "it-mgr-other-member";
    static final String TARGET = "it-mgr-target";
    static final String STRANGER = "it-mgr-stranger";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping manager controller IT");
    }

    @MockitoBean private PlayerIngestService playerIngest;
    @MockitoBean private LeagueIngestService leagueIngest;
    @MockitoBean private LeagueHistoryIngestService leagueHistoryIngest;
    @MockitoBean private SleeperClient sleeper;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;

    private long memberId;
    private long otherMemberId;
    private long targetId;
    private long strangerId;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tearDown();
        memberId = manager(MEMBER, "Mgr Member");
        otherMemberId = manager(OTHER_MEMBER, "Mgr Other Member");
        targetId = manager(TARGET, "Mgr Target");
        strangerId = manager(STRANGER, "Mgr Stranger");
        long leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nfl', 2026, ?, 'Mgr League', 10) returning id",
                Long.class, LEAGUE);
        leagueMembers.upsert(leagueId, memberId, false, "Member Team");
        leagueMembers.upsert(leagueId, otherMemberId, false, "Other Team");
        leagueMembers.upsert(leagueId, targetId, false, "Target Team");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it-mgr-%'");
    }

    private long manager(String sleeperUserId, String name) {
        return jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, sleeperUserId, name);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String who) {
        return who == null ? r : r.header("X-Sleeper-User", who);
    }

    private static MockHttpServletRequestBuilder putNote(long managerId, String who, String json) {
        return as(put("/api/managers/" + managerId + "/tendencies?sport=nfl")
                .contentType("application/json").content(json), who);
    }

    private JsonNode listAs(String who) throws Exception {
        String body = mvc.perform(as(get("/api/managers?sport=nfl"), who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private JsonNode row(JsonNode list, long managerId) {
        for (JsonNode n : list) if (n.get("managerId").asLong() == managerId) return n;
        return null;
    }

    // ------------------------------------------------------------ no identity / stranger

    @Test
    void noIdentityAndBlankIdentityAreRefusedEverywhere() throws Exception {
        for (String who : new String[] {null, "", "   "}) {
            assertEquals(0, listAs(who).size(), "list for identity [" + who + "]");
            mvc.perform(putNote(targetId, who, "{\"note\":\"x\"}")).andExpect(status().isNotFound());
            mvc.perform(as(delete("/api/managers/" + targetId + "/tendencies?sport=nfl"), who))
                    .andExpect(status().isNotFound());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from manager_note where manager_id = ?",
                Integer.class, targetId));
    }

    @Test
    void aStrangerGetsA404WhateverTheBodyAndSeesNoManagers() throws Exception {
        mvc.perform(putNote(targetId, STRANGER, "{\"note\":\"x\"}")).andExpect(status().isNotFound());
        // Stated fields and an oversized note must not turn the 404 into a 400: that would confirm the manager exists.
        mvc.perform(putNote(targetId, STRANGER, "{\"reachBias\":5}")).andExpect(status().isNotFound());
        mvc.perform(putNote(targetId, STRANGER, "{\"note\":\"" + "x".repeat(300) + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/managers/" + targetId + "/tendencies?sport=nfl"), STRANGER))
                .andExpect(status().isNotFound());
        assertEquals(0, listAs(STRANGER).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from manager_note where manager_id = ?",
                Integer.class, targetId));
    }

    // ------------------------------------------------------------ a member

    @Test
    void aMemberWritesReadsAndClearsTheirOwnNoteAndSeesOnlyManagersTheyShareALeagueWith() throws Exception {
        JsonNode list = listAs(MEMBER);
        assertNotNull(row(list, targetId));
        assertNotNull(row(list, otherMemberId));
        assertNull(row(list, strangerId), "a manager sharing no league is not listed");

        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"  reaches for QBs  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("reaches for QBs"))
                .andExpect(jsonPath("$.stated.note").value("reaches for QBs"));
        assertEquals("reaches for QBs", row(listAs(MEMBER), targetId).get("note").asText());

        mvc.perform(as(delete("/api/managers/" + targetId + "/tendencies?sport=nfl"), MEMBER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cleared").value(true));
        assertTrue(row(listAs(MEMBER), targetId).get("note").isNull());
    }

    @Test
    void aBlankNoteDeletesIt() throws Exception {
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"something\"}")).andExpect(status().isOk());
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"   \"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.note").doesNotExist());
        assertEquals(0, jdbc.queryForObject("select count(*) from manager_note where manager_id = ?",
                Integer.class, targetId));
    }

    @Test
    void aSecondMemberCannotSeeOrClearTheFirstMembersNote() throws Exception {
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"my private read\"}")).andExpect(status().isOk());

        assertTrue(row(listAs(OTHER_MEMBER), targetId).get("note").isNull());
        assertFalse(listAs(OTHER_MEMBER).toString().contains("my private read"));

        // The other member clearing "their" note must leave the first member's alone.
        mvc.perform(as(delete("/api/managers/" + targetId + "/tendencies?sport=nfl"), OTHER_MEMBER))
                .andExpect(status().isOk());
        assertEquals("my private read", row(listAs(MEMBER), targetId).get("note").asText());

        // And their own note is theirs, distinct from the first.
        mvc.perform(putNote(targetId, OTHER_MEMBER, "{\"note\":\"mine\"}")).andExpect(status().isOk());
        assertEquals("mine", row(listAs(OTHER_MEMBER), targetId).get("note").asText());
        assertEquals("my private read", row(listAs(MEMBER), targetId).get("note").asText());
    }

    // ------------------------------------------------------------ what may be written

    @Test
    void statedTendencyFieldsAreRejectedWith400AndNothingIsWritten() throws Exception {
        for (String body : new String[] {
                "{\"reachBias\":8}", "{\"unpredictability\":1.6}",
                "{\"reachBias\":8,\"unpredictability\":1.6,\"note\":\"n\"}",
                "{\"note\":\"n\",\"reachBias\":-40}"}) {
            mvc.perform(putNote(targetId, MEMBER, body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from manager_note where manager_id = ?",
                Integer.class, targetId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from manager_profile where manager_id = ? and manual_json <> '{}'::jsonb",
                Integer.class, targetId));
    }

    @Test
    void explicitNullStatedFieldsAreAcceptedBecauseTheFrontendMaySendThem() throws Exception {
        mvc.perform(putNote(targetId, MEMBER, "{\"reachBias\":null,\"unpredictability\":null,\"note\":\"ok\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aNoteLongerThan140IsA400AndExactly140IsAccepted() throws Exception {
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"" + "x".repeat(141) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"" + "x".repeat(140) + "\"}"))
                .andExpect(status().isOk());
        // Length is checked after trimming, so padding does not push a 140 note over.
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"  " + "y".repeat(140) + "  \"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aNoteWriteLeavesEveryManagersEffectiveReachBiasIdentical() throws Exception {
        JsonNode before = listAs(MEMBER);
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"a note changes no sim\"}")).andExpect(status().isOk());
        mvc.perform(putNote(otherMemberId, MEMBER, "{\"note\":\"neither does this\"}")).andExpect(status().isOk());
        JsonNode after = listAs(MEMBER);
        assertEquals(before.size(), after.size());
        for (JsonNode b : before) {
            JsonNode a = row(after, b.get("managerId").asLong());
            assertEquals(b.get("effectiveReachBias"), a.get("effectiveReachBias"));
            assertEquals(b.get("unpredictability"), a.get("unpredictability"));
            assertEquals(b.get("positionalTilt"), a.get("positionalTilt"));
            assertEquals(b.get("provenance"), a.get("provenance"));
        }
        // And no other viewer's numbers moved either.
        assertEquals(before.toString().replaceAll("\"note\":[^,}]*|\"stated\":\\{[^}]*}", ""),
                listAs(MEMBER).toString().replaceAll("\"note\":[^,}]*|\"stated\":\\{[^}]*}", ""));
    }

    // ------------------------------------------------------------ operator token

    @Test
    void theAdminTokenPassesMembershipButCannotWriteWithoutAnAuthor() throws Exception {
        // Reads: the operator sees managers, and none of anyone's notes.
        mvc.perform(putNote(targetId, MEMBER, "{\"note\":\"members only\"}")).andExpect(status().isOk());
        String body = mvc.perform(get("/api/managers?sport=nfl").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = JSON.readTree(body);
        assertNotNull(row(list, targetId));
        assertNotNull(row(list, strangerId), "the operator is not limited to shared leagues");
        assertFalse(body.contains("members only"));

        // Writes need an author: 400, not 404 (the token got past the scoping) and not a silent no-op.
        mvc.perform(putNote(targetId, null, "{\"note\":\"x\"}").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/managers/" + targetId + "/tendencies?sport=nfl")
                        .header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isBadRequest());
        // A wrong token is no token.
        mvc.perform(putNote(targetId, null, "{\"note\":\"x\"}").header("X-Admin-Token", "wrong"))
                .andExpect(status().isNotFound());
        assertEquals("members only", row(listAs(MEMBER), targetId).get("note").asText());
    }
}
