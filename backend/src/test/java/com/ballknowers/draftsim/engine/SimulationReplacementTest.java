package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.api.SimulationPermits;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static com.ballknowers.draftsim.engine.MonteCarloRunnerTest.CONFIDENCE;
import static com.ballknowers.draftsim.engine.MonteCarloRunnerTest.ctx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Live verification reported a same-user replacement that handed over the
 * permit but left the old run computing. This drives the same shape end to end
 * with a REAL MonteCarloRunner and real SimulationPermits (only the DB-backed
 * setup in SimulationService is left out): run A holds the only permit, run B
 * from the same identity and draft arrives mid-run, and A must stop early with
 * SimulationCancelledException while B completes.
 */
class SimulationReplacementTest {

    @Test
    void aReplacedRunStopsPromptlyInsteadOfComputingAlongsideItsReplacement() throws Exception {
        DraftContext c = ctx(14, 15);
        int big = 5000;

        long t0 = System.nanoTime();
        new MonteCarloRunner().run(c, 11, big, 1.0, 4L, CONFIDENCE, null);
        long fullMs = (System.nanoTime() - t0) / 1_000_000;

        SimulationPermits permits = new SimulationPermits(1, 200);
        AtomicLong aMs = new AtomicLong();
        Throwable[] aFailure = new Throwable[1];
        Thread a = Thread.ofVirtual().unstarted(() -> {
            long s = System.nanoTime();
            try (SimulationPermits.Lease lease = permits.acquire("user", "draft")) {
                new MonteCarloRunner().run(c, 11, big, 1.0, 4L, CONFIDENCE, null, lease::cancelled);
            } catch (Throwable t) {
                aFailure[0] = t;
            } finally {
                aMs.set((System.nanoTime() - s) / 1_000_000);
            }
        });
        long s = System.nanoTime();
        a.start();
        Thread.sleep(Math.max(30, fullMs / 4));

        // The replacement request runs on a VIRTUAL thread, as it does live
        // (spring.threads.virtual.enabled=true in application.yml): if the run
        // fans its iterations out as virtual threads on the same scheduler, this
        // thread is queued behind thousands of CPU-bound tasks and cannot even
        // reach acquire() until the run it means to cancel has finished.
        AtomicLong reachedAcquireMs = new AtomicLong(-1);
        SimulationResult[] bHolder = new SimulationResult[1];
        Thread bt = Thread.ofVirtual().start(() -> {
            // requireVisible() does a few blocking JDBC round trips before it
            // reaches acquire(); each one parks the virtual thread and needs a
            // carrier again to resume. Stand in for that with short parks.
            for (int i = 0; i < 6; i++) {
                try { Thread.sleep(2); } catch (InterruptedException ignored) { }
            }
            try (SimulationPermits.Lease lease = permits.acquire("user", "draft")) {
                reachedAcquireMs.set((System.nanoTime() - s) / 1_000_000);
                bHolder[0] = new MonteCarloRunner().run(c, 11, 500, 1.0, 5L, CONFIDENCE, null, lease::cancelled);
            }
        });
        bt.join(60_000);
        SimulationResult b = bHolder[0];
        a.join(30_000);
        long totalMs = (System.nanoTime() - s) / 1_000_000;

        assertNotNull(b);
        assertEquals(500, b.iterations());
        assertTrue(reachedAcquireMs.get() < fullMs * 0.6,
                "the replacement request only reached acquire() after " + reachedAcquireMs.get()
                        + " ms of a " + fullMs + " ms run: it was starved by the run's own tasks");
        assertInstanceOf(SimulationCancelledException.class, aFailure[0],
                "the replaced run should have been cancelled, got " + aFailure[0]);
        assertTrue(aMs.get() < fullMs * 0.6,
                "replaced run took " + aMs.get() + " ms against a full run of " + fullMs + " ms");
        assertEquals(1, permits.available());
        System.out.println("REPLACEMENT full=" + fullMs + "ms replaced-run=" + aMs.get() + "ms total=" + totalMs + "ms");
    }
}
