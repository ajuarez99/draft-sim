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

    /** Draft night: 12 tabs resim at once against 2 permits; every one must succeed inside the wait window. */
    @Test
    void twelveSimultaneousRequestsAtTwoPermitsAllSucceed() throws Exception {
        SimulationPermits two = new SimulationPermits(2, 3000, 8);
        LeagueMembership membership = mock(LeagueMembership.class);
        when(membership.visibleDraft(any(), any())).thenReturn(Optional.of(
                new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "complete", Map.of())));
        when(sims.simulate(any(SimulationRequest.class), any(), any())).thenAnswer(inv -> {
            Thread.sleep(200);   // stands in for a short run
            return mock(SimulationResult.class);
        });
        MockMvc m = MockMvcBuilders.standaloneSetup(new SimulationController(sims, membership, two))
                .setControllerAdvice(new ErrorHandler()).build();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(12);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < 12; i++) {
            String user = "manager" + i;
            results.add(pool.submit(() -> {
                go.await();
                return m.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Sleeper-User", user)).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<Integer> f : results) assertEquals(200, f.get(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(2, two.available());
        assertEquals(0, two.waiting());
    }

    @Test
    void aFloodPastTheWaitingCapGetsAnImmediate429() throws Exception {
        SimulationPermits tight = new SimulationPermits(1, 30_000, 1);   // 1 permit, 1 waiter
        LeagueMembership membership = mock(LeagueMembership.class);
        when(membership.visibleDraft(any(), any())).thenReturn(Optional.of(
                new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "complete", Map.of())));
        MockMvc m = MockMvcBuilders.standaloneSetup(new SimulationController(sims, membership, tight))
                .setControllerAdvice(new ErrorHandler()).build();
        SimulationPermits.Lease held = tight.acquire("holder", "d1");
        Thread waiter = new Thread(() -> { try { tight.acquire("waiter", "d1").close(); } catch (RuntimeException ignored) { } });
        waiter.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (tight.waiting() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(5);

        long t0 = System.nanoTime();
        m.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Sleeper-User", "flooder"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"));
        assertEquals(true, (System.nanoTime() - t0) / 1_000_000 < 1000);
        held.close();
        waiter.join(5000);
    }
}
