package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.api.ErrorHandler;
import com.ballknowers.draftsim.api.SimulationController;
import com.ballknowers.draftsim.api.SimulationPermits;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static com.ballknowers.draftsim.engine.MonteCarloRunnerTest.CONFIDENCE;
import static com.ballknowers.draftsim.engine.MonteCarloRunnerTest.ctx;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The same replacement scenario as the live repro, through the real controller and permits. */
class SimulationReplacementHttpTest {

    @Test
    void theBlockingEndpointStopsTheReplacedRun() throws Exception {
        DraftContext c = ctx(14, 15);
        MonteCarloRunner runner = new MonteCarloRunner();
        SimulationService sims = mock(SimulationService.class);
        when(sims.simulate(any(SimulationRequest.class), any(), any())).thenAnswer(inv -> {
            SimulationRequest req = inv.getArgument(0);
            BooleanSupplier cancelled = inv.getArgument(2);
            return runner.run(c, 11, req.iterations(), 1.0, 4L, CONFIDENCE, null, cancelled);
        });
        LeagueMembership membership = mock(LeagueMembership.class);
        when(membership.visibleDraft(any(), any())).thenReturn(Optional.of(
                new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, "complete", Map.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new SimulationController(sims, membership, new SimulationPermits(1, 200)))
                .setControllerAdvice(new ErrorHandler()).build();

        String big = "{\"draftSleeperId\":\"d1\",\"mySlot\":11,\"iterations\":5000}";
        String small = "{\"draftSleeperId\":\"d1\",\"mySlot\":11,\"iterations\":500}";

        long t0 = System.nanoTime();
        mvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(big)
                .header("X-Sleeper-User", "u1"));
        long fullMs = (System.nanoTime() - t0) / 1_000_000;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        long s = System.nanoTime();
        Future<Integer> first = pool.submit(() -> {
            int st = mvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(big)
                    .header("X-Sleeper-User", "u1")).andReturn().getResponse().getStatus();
            System.out.println("HTTPREPL first status=" + st + " after " + (System.nanoTime() - s) / 1_000_000 + "ms");
            return st;
        });
        Thread.sleep(Math.max(30, fullMs / 3));
        int second = mvc.perform(post("/api/sims").contentType(MediaType.APPLICATION_JSON).content(small)
                .header("X-Sleeper-User", "u1")).andReturn().getResponse().getStatus();
        int firstStatus = first.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        System.out.println("HTTPREPL full=" + fullMs + " second=" + second + " first=" + firstStatus);
        assertEquals(200, second);
        assertEquals(409, firstStatus, "the replaced run must be cancelled, not finish with a full body");
    }
}
