package com.ballknowers.draftsim.refresh;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SingleFlightTest {

    @Test
    void tenConcurrentCallsWithOneKeyRunTheSupplierOnceAndShareTheFuture() throws Exception {
        SingleFlight flight = new SingleFlight();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(flight.run("k", () -> {
                runs.incrementAndGet();
                await(release);
                return 7;
            }));
        }
        assertTrue(flight.isRunning("k"));
        for (CompletableFuture<Integer> f : futures) assertSame(futures.get(0), f);

        release.countDown();
        assertEquals(7, futures.get(0).get(5, TimeUnit.SECONDS));
        assertEquals(1, runs.get());
    }

    @Test
    void threeKeysSubmittedTogetherNeverRunMoreThanTheCapAtOnce() throws Exception {
        SingleFlight flight = new SingleFlight();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch capStarted = new CountDownLatch(RefreshProperties.MAX_CONCURRENT_REFRESHES);
        CountDownLatch release = new CountDownLatch(1);

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String key : List.of("a", "b", "c")) {
            futures.add(flight.run(key, () -> {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                capStarted.countDown();
                await(release);
                active.decrementAndGet();
                return key;
            }));
        }

        assertTrue(capStarted.await(5, TimeUnit.SECONDS), "the capped number of runs should start");
        // Give the extra run every chance to (wrongly) start before asserting it did not.
        Thread.sleep(200);
        assertEquals(RefreshProperties.MAX_CONCURRENT_REFRESHES, active.get());

        release.countDown();
        for (CompletableFuture<String> f : futures) f.get(5, TimeUnit.SECONDS);
        assertEquals(RefreshProperties.MAX_CONCURRENT_REFRESHES, peak.get(),
                "the third only ran once a permit freed");
    }

    @Test
    void aThrownExceptionStillReleasesTheKeyAndThePermit() throws Exception {
        SingleFlight flight = new SingleFlight();

        CompletableFuture<Integer> failed = flight.run("k", () -> {
            throw new IllegalStateException("sleeper down");
        });
        ExecutionException e = assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertFalse(flight.isRunning("k"), "the key must be released after a failure");

        assertEquals(3, flight.run("k", () -> 3).get(5, TimeUnit.SECONDS), "the next call runs again");

        // More failures than permits, then a success: no permit leaked.
        for (int i = 0; i < 4; i++) {
            String key = "f" + i;
            assertThrows(ExecutionException.class,
                    () -> flight.run(key, () -> { throw new RuntimeException("x"); }).get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, flight.run("after", () -> 1).get(5, TimeUnit.SECONDS), "no permit leaked");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
