package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.engine.AuctionGuard;
import com.ballknowers.draftsim.engine.SimulationRequest;
import com.ballknowers.draftsim.engine.SimulationService;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.mock.MockDraftService;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftFixtures;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 046 US4 (T015): the three entry points that would treat an auction's nomination order as a snake
 * order all answer the SAME 409 {@code {"error":"auction_draft","message":...}} through {@code ErrorHandler}.
 * The guard rule itself is {@link AuctionGuard} / {@code DraftRepository.modelsSnakeOrder}; a snake draft
 * must still get through (each test has a control).
 *
 * <p>The fork-to-mock guard lives inside {@code MockDraftService.createSessionFromDraft} and is tested there
 * ({@code MockDraftServiceTest}); here the service is a mock that throws what the real one throws, so this
 * pins the HTTP mapping. {@code GET /api/drafts/{id}/board} must keep answering 200 for an auction draft
 * (F1) and is pinned in {@code LeagueControllerRealBoardTest}; draft grades in {@code DraftGradesControllerTest}.
 */
class AuctionGuardMvcTest {

    private static final String SIM_BODY = "{\"draftSleeperId\":\"a1\",\"mySlot\":1,\"iterations\":100}";

    private LeagueMembership membership;
    private DraftRepository drafts;
    private SimulationService sims;
    private MockMvc simMvc;

    @BeforeEach
    void setUp() {
        membership = mock(LeagueMembership.class);
        drafts = mock(DraftRepository.class);
        sims = mock(SimulationService.class);
        simMvc = MockMvcBuilders.standaloneSetup(new SimulationController(sims, membership, new SimulationPermits(1)))
                .setControllerAdvice(new ErrorHandler()).build();
        when(membership.visibleDraft(any(), anyString())).thenAnswer(inv -> drafts.bySleeperId(inv.getArgument(1)));
    }

    private void expectAuctionRefusal(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        r.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("auction_draft"))
                .andExpect(jsonPath("$.message").value(AuctionGuard.MESSAGE));
    }

    @Test
    void simsRefuseAnAuctionDraftWith409() throws Exception {
        when(drafts.bySleeperId("a1")).thenReturn(Optional.of(
                DraftFixtures.auctionDraftRow(1L, 10L, "a1", 2026, 23, 10, "complete", 200)));

        expectAuctionRefusal(simMvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON)
                .content(SIM_BODY).header("X-Sleeper-User", "me")));
        verify(sims, never()).simulate(any(SimulationRequest.class), any(), any());
    }

    @Test
    void theSimStreamRefusesAnAuctionDraftWith409BeforeAnEmitterExists() throws Exception {
        when(drafts.bySleeperId("a1")).thenReturn(Optional.of(
                DraftFixtures.auctionDraftRow(1L, 10L, "a1", 2026, 23, 10, "drafting", 200)));

        expectAuctionRefusal(simMvc.perform(post("/api/sims/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(SIM_BODY).header("X-Sleeper-User", "me")));
        verify(sims, never()).simulate(any(SimulationRequest.class), any(), any());
    }

    @Test
    void aSnakeDraftIsNotRefusedBySims() throws Exception {
        when(drafts.bySleeperId("s1")).thenReturn(Optional.of(
                DraftFixtures.draftRow(2L, 10L, "s1", 2026, 15, 14, "complete", java.util.Map.of())));
        when(sims.simulate(any(SimulationRequest.class), any(), any())).thenReturn(mock(com.ballknowers.draftsim.engine.SimulationResult.class));

        simMvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON)
                        .content(SIM_BODY.replace("a1", "s1")).header("X-Sleeper-User", "me"))
                .andExpect(status().isOk());
    }

    @Test
    void forkToMockMapsTheServicesRefusalTo409() throws Exception {
        MockDraftService mocks = mock(MockDraftService.class);
        when(mocks.createSessionFromDraft(anyString(), any(), any())).thenThrow(new AuctionGuard.AuctionDraft());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MockDraftController(mocks, membership))
                .setControllerAdvice(new ErrorHandler()).build();

        expectAuctionRefusal(mvc.perform(post("/api/mocks/from-draft/a1").header("X-Sleeper-User", "me")));
    }

    @Test
    void aManualPickMapsTheRefusalTo409AndWritesNothing() throws Exception {
        LeagueRepository leagues = mock(LeagueRepository.class);
        LeagueController controller = new LeagueController(leagues, drafts, mock(ProfileService.class),
                mock(BoardService.class), mock(LiveDraftPoller.class), mock(ManagerRepository.class),
                mock(PlayerRepository.class), mock(OwnerProperties.class), membership);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ErrorHandler()).build();
        when(drafts.bySleeperId("a1")).thenReturn(Optional.of(
                DraftFixtures.auctionDraftRow(1L, 10L, "a1", 2026, 23, 10, "drafting", 200)));

        expectAuctionRefusal(mvc.perform(post("/api/drafts/a1/picks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickNo\":3,\"sleeperPlayerId\":\"4046\"}").header("X-Sleeper-User", "me")));
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }
}
