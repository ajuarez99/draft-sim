package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.profile.Provenance;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * specs/021-codebase-cleanup T033, the oracle for converting LeagueController's REST
 * success bodies into records. It uses standalone MockMvc with mocked collaborators, and
 * was written green against the map code first.
 *
 * <p>The live-stream {@code state} frame stays a map, because SSE payloads are out of
 * scope (research R3 rule 7). But its {@code recentPicks} come from the same
 * {@code PickNaming.row} as {@code GET /board}'s picks, and converting that row converts
 * what the live draft room receives. So the frame is pinned here too, called directly
 * since the emitter has no read-back seam. Its {@code serverTime} is the wall clock, so
 * it is blanked before comparing.
 *
 * <p>Traps covered:
 * <ul>
 *   <li>{@code mySlot} null and set;</li>
 *   <li>relative reach null and set;</li>
 *   <li>a seat falling back to the neutral profile;</li>
 *   <li>{@code positionalTilt} order, checked order-sensitively;</li>
 *   <li>a pick off today's board (fallback player);</li>
 *   <li>a pick with no player or a vanished player (skipped);</li>
 *   <li>an unmapped seat ("Slot N", null avatar);</li>
 *   <li>a null {@code adpAtDraft} and a null {@code status};</li>
 *   <li>a free agent's null team on {@code /api/board}.</li>
 * </ul>
 */
class LeagueControllerCharacterizationTest {

    private static final String DRAFT = "D-1";
    private static final String ME = "u-me";

    private LeagueRepository leagues;
    private DraftRepository drafts;
    private ProfileService profiles;
    private BoardService boards;
    private LiveDraftPoller poller;
    private ManagerRepository managers;
    private PlayerRepository players;
    private LeagueMembership membership;
    private MockMvc mvc;
    private LeagueController controller;

    private static DraftRepository.DraftRow draft(String status) {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("2", 12L);
        slots.put("1", 11L);
        slots.put("3", 13L);
        return new DraftRepository.DraftRow(7L, 100L, DRAFT, 2026, 3, 3, status, slots, 0);
    }

    private static final LeagueRepository.LeagueRow LEAGUE = new LeagueRepository.LeagueRow(
            100L, Sport.NFL, "L-2026", "Ball Knowers", 2026, 3, List.of("QB", "RB", "FLEX"), 1.0, null, "in_season");

    private static Player player(long id, String name, String team) {
        return new Player(id, Sport.NFL, "s" + id, name, List.of(Position.RB), team, "Active", null, 25, 3);
    }

    private void build(OwnerProperties owner) {
        controller = new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner, membership);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        drafts = mock(DraftRepository.class);
        profiles = mock(ProfileService.class);
        boards = mock(BoardService.class);
        poller = mock(LiveDraftPoller.class);
        managers = mock(ManagerRepository.class);
        players = mock(PlayerRepository.class);
        membership = mock(LeagueMembership.class);
        build(new OwnerProperties(null));

        when(leagues.byId(100L)).thenReturn(Optional.of(LEAGUE));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of(ME, 11L, "u-owner", 13L));
        when(managers.names()).thenReturn(Map.of(11L, "popsharky", 12L, "kieriskash"));
        when(managers.avatarIds()).thenReturn(Map.of(11L, "av11"));
        when(boards.currentBoard(Sport.NFL)).thenReturn(List.of(
                new BoardEntry(player(1L, "Bijan Robinson", "ATL"), 1.5, 1),
                new BoardEntry(player(2L, "Free Agent RB", null), 140.25, 44)));
        when(players.findAll(Sport.NFL)).thenReturn(List.of(
                player(1L, "Bijan Robinson", "ATL"), player(2L, "Free Agent RB", null),
                player(3L, "Retired Guy", "NYG")));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String who) {
        return who == null ? r : r.header("X-Sleeper-User", who);
    }

    private void check(MockHttpServletRequestBuilder req, int status, String golden, String... orderSensitive) throws Exception {
        MvcResult res = mvc.perform(req).andReturn();
        assertEquals(status, res.getResponse().getStatus(), golden + " status");
        String body = res.getResponse().getContentAsString();
        if (body.isEmpty()) return;
        GoldenJson.assertJsonMatchesGolden(body, "league/" + golden, orderSensitive);
    }

    // ------------------------------------------------------------------ seats

    private void stubSeats(String status) {
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft(status)));
        Map<Position, Double> tilt = new LinkedHashMap<>();
        tilt.put(Position.WR, 1.2);
        tilt.put(Position.QB, 0.7);
        ManagerProfile p11 = new ManagerProfile(11L, "popsharky", -2.345, tilt, 1.1, "reaches for WRs", 2, 30, Provenance.FITTED, "av11");
        ManagerProfile p12 = new ManagerProfile(12L, "kieriskash", 0.0, Map.of(), 1.0, null, 0, 0, Provenance.STATED, null);
        // Manager 13 has no profile, so the seat falls back to ManagerProfile.neutral.
        when(profiles.fit(Sport.NFL)).thenReturn(new ProfileService.Fit(Map.of(11L, p11, 12L, p12), null, 30,
                Map.of(), Map.of(11L, -1.234), Map.of(11L, 0.567)));
        when(drafts.reversalRound(7L)).thenReturn(Optional.of(new DraftRepository.ReversalRound(0, 3)));
    }

    @Test
    void seatsForAManagerInTheDraft() throws Exception {
        stubSeats("drafting");
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        check(as(get("/api/drafts/" + DRAFT + "/seats"), ME), 200, "seats-member", "/seats/*/positionalTilt");
    }

    @Test
    void seatsAnonymousWithNoOverrideAndANullStatus() throws Exception {
        stubSeats(null);
        when(drafts.reversalRound(7L)).thenReturn(Optional.empty());
        check(get("/api/drafts/" + DRAFT + "/seats"), 200, "seats-anonymous", "/seats/*/positionalTilt");
    }

    @Test
    void seatsWithAConfiguredOwnerFallback() throws Exception {
        build(new OwnerProperties("u-owner"));
        stubSeats("pre_draft");
        check(get("/api/drafts/" + DRAFT + "/seats"), 200, "seats-owner-fallback", "/seats/*/positionalTilt");
    }

    @Test
    void seatsHidden() throws Exception {
        check(as(get("/api/drafts/nope/seats"), ME), 404, "seats-404");
    }

    // ------------------------------------------------------------------ reversal round

    @Test
    void reversalRoundEveryOutcome() throws Exception {
        String url = "/api/drafts/" + DRAFT + "/reversal-round";
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft("complete")));
        check(as(put(url), ME).contentType("application/json").content("{\"reversalRound\":2}"), 409, "reversal-409");
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft("pre_draft")));
        check(as(put(url), ME).contentType("application/json").content("{\"reversalRound\":2}"), 403, "reversal-403");
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        check(as(put(url), ME).contentType("application/json").content("{\"reversalRound\":9}"), 400, "reversal-400");
        when(drafts.reversalRound(7L)).thenReturn(Optional.of(new DraftRepository.ReversalRound(0, 2)));
        check(as(put(url), ME).contentType("application/json").content("{\"reversalRound\":2}"), 200, "reversal-set");
        when(drafts.reversalRound(7L)).thenReturn(Optional.empty());
        check(as(put(url), ME).contentType("application/json").content("{\"reversalRound\":null}"), 200, "reversal-cleared");
    }

    // ------------------------------------------------------------------ real board and the picks it shares

    private static List<DraftRepository.PickRow> picks() {
        return List.of(
                new DraftRepository.PickRow(7L, 1, 1, 1, 11L, 1L, 2.0),          // on the board, adp captured
                new DraftRepository.PickRow(7L, 2, 1, 2, 12L, 3L, null),         // off the board: fallback player
                new DraftRepository.PickRow(7L, 3, 1, 3, null, 2L, null),        // unmapped seat
                new DraftRepository.PickRow(7L, 4, 2, 3, 13L, null, null),       // no player yet: skipped
                new DraftRepository.PickRow(7L, 5, 2, 2, 12L, 999L, null));      // player row gone: skipped
    }

    @Test
    void realBoardNamesEveryPick() throws Exception {
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft("drafting")));
        when(drafts.picks(7L)).thenReturn(picks());
        check(as(get("/api/drafts/" + DRAFT + "/board"), ME), 200, "real-board");
    }

    @Test
    void theLiveStateFrameCarriesTheSamePickRows() {
        when(poller.isTracking(7L)).thenReturn(true);
        Map<String, Object> frame = controller.statePayload(DRAFT, draft("drafting"),
                new LiveDraftPoller.LiveSnapshot("drafting", 5, 5, 3, 3), picks(), controller.pickNaming(Sport.NFL));
        JsonNode node = GoldenJson.MAPPER.valueToTree(frame);
        ((ObjectNode) node).put("serverTime", "<wall clock>");
        GoldenJson.assertTreeMatchesGolden(node, "league/live-state-frame");
    }

    // ------------------------------------------------------------------ track

    @Test
    void trackReportsWhatThePollerSaw() throws Exception {
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft("drafting")));
        when(poller.track(any())).thenReturn(new LiveDraftPoller.TrackResult(true, null, 2, false, true));
        check(as(post("/api/drafts/" + DRAFT + "/track"), ME), 200, "track-started-null-status");
        when(poller.track(any())).thenReturn(new LiveDraftPoller.TrackResult(false, "drafting", 3, true, true));
        check(as(post("/api/drafts/" + DRAFT + "/track"), ME), 200, "track-already");
    }

    // ------------------------------------------------------------------ manual pick

    @Test
    void recordPickEveryOutcome() throws Exception {
        String url = "/api/drafts/" + DRAFT + "/picks";
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft("complete")));
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":1,\"sleeperPlayerId\":\"s1\"}"), 409, "pick-409-status");
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(draft("drafting")));
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":99,\"sleeperPlayerId\":\"s1\"}"), 400, "pick-400-range");
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":1}"), 400, "pick-400-player");
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("s1", 1L, "s2", 2L));
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":1,\"sleeperPlayerId\":\"nobody\"}"), 400, "pick-400-unknown");
        when(drafts.otherPickOfPlayer(7L, 2L, 1)).thenReturn(Optional.of(4));
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":1,\"sleeperPlayerId\":\"s2\"}"), 409, "pick-409-elsewhere");
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":2,\"sleeperPlayerId\":\"s1\"}"), 403, "pick-403-not-your-seat");
        when(drafts.otherPickOfPlayer(anyLong(), anyLong(), anyInt())).thenReturn(Optional.empty());
        check(as(post(url), ME).contentType("application/json").content("{\"pickNo\":1,\"sleeperPlayerId\":\"s1\"}"), 200, "pick-ok");
    }

    @Test
    void recordPickIntoAnUnmappedSeatIsUnattributed() throws Exception {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("1", 11L);
        when(membership.visibleDraft(any(), eq(DRAFT))).thenReturn(Optional.of(
                new DraftRepository.DraftRow(7L, 100L, DRAFT, 2026, 3, 3, "drafting", slots, 0)));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("s1", 1L));
        when(drafts.otherPickOfPlayer(anyLong(), anyLong(), anyInt())).thenReturn(Optional.empty());
        check(as(post("/api/drafts/" + DRAFT + "/picks"), ME).contentType("application/json")
                .content("{\"pickNo\":2,\"sleeperPlayerId\":\"s1\"}"), 200, "pick-ok-unattributed");
    }

    // ------------------------------------------------------------------ /api/board

    @Test
    void engineBoardIncludingAFreeAgent() throws Exception {
        when(boards.currentBoardDate(Sport.NFL)).thenReturn(Optional.of(LocalDate.parse("2026-09-01")));
        when(boards.picksWithAdpAtTime()).thenReturn(42);
        check(get("/api/board").param("limit", "5"), 200, "engine-board");
        when(boards.currentBoardDate(Sport.NFL)).thenReturn(Optional.empty());
        check(get("/api/board").param("limit", "1"), 200, "engine-board-undated");
    }
}
