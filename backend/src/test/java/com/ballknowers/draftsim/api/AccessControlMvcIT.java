package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.LeagueHistoryIngestService;
import com.ballknowers.draftsim.ingest.LeagueIngestService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole HTTP surface of claude/audit-2026-09-28/01 and /04 (option D), driven through
 * MockMvc so the real filters, interceptor, CORS mapping and controllers are all in play.
 * The controller-level ITs call beans directly and so cannot see the interceptor or CORS.
 *
 * <p>Sleeper-facing services are mocked; Postgres is real. SKIPS when the local Postgres is
 * unreachable, so the caller must read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlMvcIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    static final String LEAGUE = "it-acl-league";
    static final String COMMISSIONER = "it-acl-commissioner";
    static final String MEMBER = "it-acl-member";
    static final String STRANGER = "it-acl-stranger";
    static final String NEW_LEAGUE = "it-acl-brand-new-league";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping access-control MVC IT");
    }

    @MockitoBean private PlayerIngestService playerIngest;
    @MockitoBean private LeagueIngestService leagueIngest;
    @MockitoBean private LeagueHistoryIngestService leagueHistoryIngest;
    @MockitoBean private SleeperClient sleeper;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeagueMemberRepository leagueMembers;
    @Autowired private com.ballknowers.draftsim.store.MockDraftRepository mockDrafts;

    private long leagueId;
    private long commissionerId;
    private long memberId;
    private long mockId;

    @BeforeEach
    void setUp() {
        tearDown();
        commissionerId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, COMMISSIONER, "ACL Commissioner");
        memberId = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, MEMBER, "ACL Member");
        jdbc.update("insert into manager (sleeper_user_id, display_name) values (?, ?)", STRANGER, "ACL Stranger");
        leagueId = jdbc.queryForObject(
                "insert into league (sport, season, sleeper_id, name, total_rosters) values ('nfl', 2026, ?, 'ACL League', 10) returning id",
                Long.class, LEAGUE);
        leagueMembers.upsert(leagueId, commissionerId, true, "Commish Team");
        leagueMembers.upsert(leagueId, memberId, false, "Member Team");
        jdbc.update("""
                insert into draft (league_id, sleeper_draft_id, season, rounds, teams, draft_type, status, slot_to_manager)
                values (?, 'it-acl-draft', 2026, 15, 10, 'snake', 'pre_draft', ?::jsonb)
                """, leagueId, "{\"1\": " + memberId + "}");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from mock_draft_session where owner_sleeper_user_id like 'it-acl-%'"
                + " or (owner_sleeper_user_id is null and rng_seed = 1 and seats_json = '[]'::jsonb)");
        jdbc.update("delete from league where sleeper_id = ?", LEAGUE);
        jdbc.update("delete from manager where sleeper_user_id like 'it-acl-%'");
    }

    // ---------------------------------------------------------------- blank identity denied

    /** Production showed 200 header-less vs 404 for a stranger; both must now be 404. */
    @Test
    void leagueRoutesAre404WithNoIdentityAndForAStranger() throws Exception {
        for (String path : List.of("analysis", "history", "power", "ballot", "superlatives", "conduct-list",
                "roster-management", "transactions", "expected-wins", "forecast", "weekly-report/1")) {
            mvc.perform(get("/api/leagues/" + LEAGUE + "/" + path)).andExpect(status().isNotFound());
            mvc.perform(get("/api/leagues/" + LEAGUE + "/" + path).header("X-Sleeper-User", ""))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/leagues/" + LEAGUE + "/" + path).header("X-Sleeper-User", STRANGER))
                    .andExpect(status().isNotFound());
        }
        // The real member is not turned away by the same rule (the route may still 4xx/5xx for
        // want of ingested data -- what matters is that it is not the scoping 404, checked via ballot,
        // which needs no ingested data).
        mvc.perform(get("/api/leagues/" + LEAGUE + "/ballot").header("X-Sleeper-User", MEMBER))
                .andExpect(status().isOk());
    }

    @Test
    void theDraftListIsEmptyWithNoIdentityButNotForAMember() throws Exception {
        mvc.perform(get("/api/drafts")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/drafts").header("X-Sleeper-User", "  ")).andExpect(content().json("[]"));
        mvc.perform(get("/api/drafts").header("X-Sleeper-User", STRANGER)).andExpect(content().json("[]"));
        mvc.perform(get("/api/drafts").header("X-Sleeper-User", MEMBER))
                .andExpect(jsonPath("$[?(@.sleeperDraftId == 'it-acl-draft')]").isNotEmpty());
    }

    @Test
    void draftRoutesAre404WithNoIdentity() throws Exception {
        mvc.perform(get("/api/drafts/it-acl-draft/seats")).andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/it-acl-draft/board")).andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/it-acl-draft/pool")).andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/it-acl-draft/pool").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/it-acl-draft/pool").header("X-Sleeper-User", MEMBER)).andExpect(status().isOk());
        mvc.perform(get("/api/drafts/it-acl-draft/live-stream")).andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/it-acl-draft/live-stream").param("user", ""))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/drafts/it-acl-draft/track")).andExpect(status().isNotFound());
        mvc.perform(get("/api/drafts/it-acl-draft/seats").header("X-Sleeper-User", MEMBER)).andExpect(status().isOk());
    }

    /** The manual pick was the worst write: any header-less curl could overwrite any seat. */
    @Test
    void aManualPickWithNoIdentityIsRefusedAndAnOperatorMayStillRecordOne() throws Exception {
        String body = "{\"pickNo\":1,\"sleeperPlayerId\":\"it-acl-no-such-player\"}";
        mvc.perform(post("/api/drafts/it-acl-draft/picks").contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        // The fixture draft is pre_draft, which manual picks now refuse (audit 03): 409, not a write.
        mvc.perform(post("/api/drafts/it-acl-draft/picks").contentType("application/json").content(body)
                        .header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isConflict());
        jdbc.update("update draft set status = 'drafting' where sleeper_draft_id = 'it-acl-draft'");
        // With the token the request gets past the membership check: the unknown player is then a 400,
        // not the 404 above -- proof it reached the write path without writing anything.
        mvc.perform(post("/api/drafts/it-acl-draft/picks").contentType("application/json").content(body)
                        .header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mockRoutesRefuseNoIdentity() throws Exception {
        mockId = createMockAs(MEMBER);
        long legacyId = createMockAs(null);   // V8's unowned legacy row
        mvc.perform(get("/api/mocks/" + mockId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/mocks/" + mockId).header("X-Sleeper-User", STRANGER)).andExpect(status().isNotFound());
        mvc.perform(post("/api/mocks/" + mockId + "/pick").contentType("application/json")
                .content("{\"sleeperPlayerId\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/mocks")).andExpect(content().json("[]"));
        // An unowned legacy session stays usable by any SIGNED-IN identity, never by a blank one.
        mvc.perform(get("/api/mocks/" + legacyId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/mocks/" + legacyId).header("X-Sleeper-User", STRANGER))
                .andExpect(notTheScopingNotFound());
        // Creating one needs an identity too, or it would mint an unowned, everyone's-room session.
        mvc.perform(post("/api/mocks").contentType("application/json")
                .content("{\"sport\":\"nfl\",\"teams\":8,\"userSlot\":1,\"managerSeats\":{}}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mocks/from-draft/it-acl-draft")).andExpect(status().isUnauthorized());
        // The owner is unaffected.
        mvc.perform(get("/api/mocks/" + mockId).header("X-Sleeper-User", MEMBER)).andExpect(notTheScopingNotFound());
        // And the operator override works.
        mvc.perform(get("/api/mocks/" + mockId).header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(notTheScopingNotFound());
    }

    @Test
    void theRefreshRoutesAre404WithNoIdentity() throws Exception {
        mvc.perform(post("/api/leagues/" + LEAGUE + "/refresh")).andExpect(status().isNotFound());
        mvc.perform(get("/api/leagues/" + LEAGUE + "/refresh")).andExpect(status().isNotFound());
        mvc.perform(post("/api/leagues/" + LEAGUE + "/refresh").header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/refresh/players").param("sport", "nfl")).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------- /api/ingest/** gate

    @Test
    void ingestRoutesRefuseWithoutTheAdminToken() throws Exception {
        List<String> routes = List.of("/api/ingest/players?sport=nfl", "/api/ingest/adp?sport=nfl",
                "/api/ingest/board?sport=nfl", "/api/ingest/league/" + LEAGUE, "/api/ingest/all/" + LEAGUE,
                "/api/ingest/league-history/" + LEAGUE, "/api/ingest/transactions/" + LEAGUE,
                "/api/ingest/player-games/" + LEAGUE,
                "/api/ingest/projections?sport=nfl&season=2026&fromWeek=1&toWeek=2");
        for (String route : routes) {
            // no token, a wrong token, a blank token, a signed-in member's identity alone
            expectAdminRefusal(mvc.perform(post(route)));
            expectAdminRefusal(mvc.perform(post(route).header("X-Admin-Token", "not-the-token")));
            expectAdminRefusal(mvc.perform(post(route).header("X-Admin-Token", "")));
            expectAdminRefusal(mvc.perform(post(route).header("X-Sleeper-User", COMMISSIONER)));
        }
        verifyNoInteractions(playerIngest, leagueIngest, leagueHistoryIngest);
    }

    @Test
    void theAdminTokenGrantsIngest() throws Exception {
        when(playerIngest.ingest(Sport.NFL)).thenReturn(new PlayerIngestService.Result(1, 1, false, 0));
        mvc.perform(post("/api/ingest/players?sport=nfl").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isOk());
        verify(playerIngest).ingest(Sport.NFL);
    }

    // ------------------------------------------------------------------------------ CORS

    /** The browser sends this preflight before any commissioner write; it must pass and allow the header. */
    @Test
    void aPreflightAllowsTheAdminTokenHeaderFromTheDevOrigin() throws Exception {
        mvc.perform(options("/api/leagues/" + LEAGUE + "/conduct-list")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-sleeper-user,x-admin-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsStringIgnoringCase("X-Admin-Token")));
        // And a preflight to an ingest route is not turned into a 403 by the gate.
        mvc.perform(options("/api/ingest/players")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-admin-token"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------- commissioner-only actions

    /**
     * Commissioner actions are an honour system since 2026-10-05 (claude/audit-2026-09-28/04,
     * amended): no commissioner ever holds the operator's admin token, so asking for it locked
     * every one of them out. The commissioner identity is still checked.
     */
    @Test
    void commissionerActionsNeverAskForTheAdminToken() throws Exception {
        String conduct = "{\"playerId\":\"x\",\"reason\":\"r\",\"appliesFromWeek\":1}";
        expectNoAdminRefusal(mvc.perform(post("/api/leagues/" + LEAGUE + "/conduct-list")
                .contentType("application/json").content(conduct).header("X-Sleeper-User", COMMISSIONER)));
        expectNoAdminRefusal(mvc.perform(delete("/api/leagues/" + LEAGUE + "/conduct-list/1")
                .header("X-Sleeper-User", COMMISSIONER)));
        expectNoAdminRefusal(mvc.perform(post("/api/leagues/" + LEAGUE + "/power/commissioner")
                .contentType("application/json").content("{\"season\":2026,\"week\":1,\"rosterIds\":[1]}")
                .header("X-Sleeper-User", COMMISSIONER)));
        expectNoAdminRefusal(mvc.perform(post("/api/leagues/" + LEAGUE + "/power/compute?season=2026&week=1")
                .header("X-Sleeper-User", COMMISSIONER)));
        expectNoAdminRefusal(mvc.perform(post("/api/leagues/" + LEAGUE + "/power/compute?season=2026&week=1")
                .header("X-Sleeper-User", COMMISSIONER).header("X-Admin-Token", "wrong")));
    }

    @Test
    void aMemberWhoIsNotTheCommissionerIsStillRefused() throws Exception {
        // power/compute was labelled commissioner but only checked membership (audit 10); and
        // power/backfill sat behind History's commissioner-only button. Both still check.
        mvc.perform(post("/api/leagues/" + LEAGUE + "/power/compute?season=2026&week=1")
                        .header("X-Sleeper-User", MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(post("/api/leagues/" + LEAGUE + "/power/backfill")
                        .header("X-Sleeper-User", MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void theAdminTokenAloneDoesNotMakeAMemberACommissioner() throws Exception {
        mvc.perform(post("/api/leagues/" + LEAGUE + "/power/compute?season=2026&week=1")
                        .header("X-Sleeper-User", MEMBER).header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(post("/api/leagues/" + LEAGUE + "/power/backfill")
                        .header("X-Sleeper-User", MEMBER).header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void theCommissionerCanBackfillWithoutTheAdminToken() throws Exception {
        // The gate must not lock out the one caller History's button is shown to.
        mvc.perform(post("/api/leagues/" + LEAGUE + "/power/backfill")
                        .header("X-Sleeper-User", COMMISSIONER))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------- member-scoped setup routes

    @Test
    void setupRoutesNeedAnIdentityAndMembership() throws Exception {
        // No identity: 401, and nothing is crawled.
        mvc.perform(post("/api/setup/league/" + NEW_LEAGUE)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/setup/league-history/" + LEAGUE).header("X-Sleeper-User", " "))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/setup/adp?sport=nfl")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/setup/board?sport=nfl")).andExpect(status().isUnauthorized());

        // A stranger naming a league they are not in (Sleeper says so): 404, nothing crawled.
        when(sleeper.leagueUsers(NEW_LEAGUE)).thenReturn(List.of(Map.of("user_id", "someone-else")));
        mvc.perform(post("/api/setup/league/" + NEW_LEAGUE).header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
        // ...including a league already in our database that they cannot see.
        when(sleeper.leagueUsers(LEAGUE)).thenReturn(List.of(Map.of("user_id", COMMISSIONER)));
        mvc.perform(post("/api/setup/league/" + LEAGUE).header("X-Sleeper-User", STRANGER))
                .andExpect(status().isNotFound());
        // A Sleeper failure is a refusal, not a 500.
        when(sleeper.leagueUsers("it-acl-broken")).thenThrow(new RuntimeException("sleeper 404"));
        mvc.perform(post("/api/setup/league/it-acl-broken").header("X-Sleeper-User", MEMBER))
                .andExpect(status().isNotFound());
        // A user we have never heard of has no league here, so the sport-wide rebuilds are refused.
        mvc.perform(post("/api/setup/board?sport=nfl").header("X-Sleeper-User", "it-acl-nobody"))
                .andExpect(status().isForbidden());
        verify(leagueIngest, never()).ingestChain(any(), anyString());
        verify(leagueHistoryIngest, never()).ingestChain(any(), anyString(), any());
    }

    @Test
    void aBrandNewLeagueCanBeAddedByAUserWhoAppearsInItOnSleeper() throws Exception {
        when(sleeper.leagueUsers(NEW_LEAGUE)).thenReturn(List.of(Map.of("user_id", STRANGER)));
        when(leagueIngest.inferSport(NEW_LEAGUE)).thenReturn(Sport.NFL);

        mvc.perform(post("/api/setup/league/" + NEW_LEAGUE).header("X-Sleeper-User", STRANGER))
                .andExpect(status().isOk());

        verify(leagueIngest).ingestChain(Sport.NFL, NEW_LEAGUE);
    }

    @Test
    void anExistingMemberCanLoadPastSeasonsWithoutASleeperLookup() throws Exception {
        when(leagueIngest.inferSport(LEAGUE)).thenReturn(Sport.NFL);

        mvc.perform(post("/api/setup/league-history/" + LEAGUE).header("X-Sleeper-User", MEMBER))
                .andExpect(status().isOk());

        verify(leagueHistoryIngest).ingestChain(Sport.NFL, LEAGUE, java.util.Set.of());
        verifyNoInteractions(sleeper);
    }

    // ------------------------------------------------------------------------------ helpers

    private static void expectAdminRefusal(ResultActions r) throws Exception {
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("admin_token_required"));
    }

    /** Whatever the outcome (200, 400, 404 on a missing entry), it is never the admin-token refusal. */
    private static void expectNoAdminRefusal(ResultActions r) throws Exception {
        r.andExpect(result -> {
            String body = result.getResponse().getContentAsString();
            if (body.contains(AdminGateInterceptor.REFUSAL_CODE)) {
                throw new AssertionError("asked for the admin token: " + result.getResponse().getStatus() + " " + body);
            }
        });
    }

    /**
     * Inserted through the repository, not POST /api/mocks: creating one needs a built board,
     * which this test has no reason to depend on. Reading it back can therefore fail for want of
     * a board (a 409), so the owner and operator assertions say "not the scoping 404" rather than 200.
     */
    private long createMockAs(String sleeperUserId) {
        return mockDrafts.createSession(Sport.NFL, 8, 15, List.of("QB", "BN"), 1.0, "[]", 1, 1L,
                null, null, sleeperUserId, null, 0, null);
    }

    private static org.springframework.test.web.servlet.ResultMatcher notTheScopingNotFound() {
        return result -> org.junit.jupiter.api.Assertions.assertNotEquals(404, result.getResponse().getStatus(),
                "an allowed caller must not get the scoping 404");
    }
}
