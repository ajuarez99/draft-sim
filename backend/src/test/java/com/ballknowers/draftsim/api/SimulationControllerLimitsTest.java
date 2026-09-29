package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.SimulationRequest;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.engine.SimulationService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** audit 05: 429 + Retry-After on both endpoints, and the permit always comes back. */
class SimulationControllerLimitsTest {

    private static final String BODY = "{\"draftSleeperId\":\"d1\",\"mySlot\":1,\"iterations\":100}";

    private SimulationService sims;
    private SimulationPermits permits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sims = mock(SimulationService.class);
        LeagueMembership membership = mock(LeagueMembership.class);
        when(membership.visibleDraft(any(), any())).thenReturn(Optional.of(
                new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "complete", Map.of())));
        permits = new SimulationPermits(1);
        mvc = MockMvcBuilders.standaloneSetup(new SimulationController(sims, membership, permits))
                .setControllerAdvice(new ErrorHandler())
                .build();
    }

    @Test
    void blockingEndpointRefusesWith429AndRetryAfterWhenNoPermitIsFree() throws Exception {
        try (SimulationPermits.Lease held = permits.acquire("someone-else")) {
            mvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .header("X-Sleeper-User", "me"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "2"))
                    .andExpect(jsonPath("$.error").exists());
        }
        verify(sims, never()).simulate(any(), any(), any());
    }

    @Test
    void streamingEndpointRefusesWith429BeforeAnEmitterExists() throws Exception {
        try (SimulationPermits.Lease held = permits.acquire("someone-else")) {
            mvc.perform(post("/api/sims/stream").contentType(MediaType.APPLICATION_JSON).content(BODY)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .header("X-Sleeper-User", "me"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "2"))
                    .andExpect(jsonPath("$.error").exists());
        }
        verify(sims, never()).simulate(any(), any(), any());
    }

    @Test
    void theBlockingEndpointReleasesItsPermitOnSuccessAndOnFailure() throws Exception {
        SimulationResult result = mock(SimulationResult.class);
        when(sims.simulate(any(SimulationRequest.class), any(), any())).thenReturn(result);
        mvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Sleeper-User", "me"));
        assertEquals(1, permits.available());

        when(sims.simulate(any(SimulationRequest.class), any(), any()))
                .thenThrow(new IllegalStateException("board not built"));
        mvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Sleeper-User", "me")).andExpect(status().isConflict());
        assertEquals(1, permits.available());
    }

    @Test
    void theStreamReleasesItsPermitWhenTheRunFails() throws Exception {
        when(sims.simulate(any(SimulationRequest.class), any(), any()))
                .thenThrow(new IllegalStateException("board not built"));
        mvc.perform(post("/api/sims/stream").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Sleeper-User", "me"));
        long deadline = System.currentTimeMillis() + 5000;
        while (permits.available() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, permits.available());
    }

    @Test
    void theClampedIterationCountIsWhatTheRequestCarries() {
        assertEquals(SimulationRequest.MAX_ITERATIONS,
                new SimulationRequest("d", 1, 20000, null, null, null, null).iterations());
        assertEquals(1000, new SimulationRequest("d", 1, 0, null, null, null, null).iterations());
    }
}
