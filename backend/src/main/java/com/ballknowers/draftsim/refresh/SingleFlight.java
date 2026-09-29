package com.ballknowers.draftsim.refresh;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * At most one run per key, at most {@link RefreshProperties#MAX_CONCURRENT_REFRESHES}
 * runs at once (specs/009-auto-data-refresh, research R4, FR-003, FR-014).
 *
 * <p>A second {@link #run} for a key that is in flight returns <b>the same
 * future</b> instead of starting another. The key is released when the run
 * ends, whether it returned or threw, so the next call runs again -- a key that
 * leaked on failure would make a league un-refreshable until restart.
 *
 * <p>Runs execute on virtual threads; the semaphore is the cap, not the pool.
 * Because a run blocks on the permit, a run that itself waits on another
 * {@code SingleFlight} must not share this instance with it: the league-chain
 * flight waits on per-sport-season flights, so they are separate instances. One
 * shared semaphore could deadlock with every permit held by a waiter.
 */
public final class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore permits = new Semaphore(RefreshProperties.MAX_CONCURRENT_REFRESHES);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> run(String key, Supplier<T> work) {
        CompletableFuture<T> mine = new CompletableFuture<>();
        CompletableFuture<?> running = inFlight.putIfAbsent(key, mine);
        if (running != null) return (CompletableFuture<T>) running;

        try {
            executor.execute(() -> {
                boolean acquired = false;
                try {
                    permits.acquire();
                    acquired = true;
                    T result = work.get();
                    // Release the key BEFORE completing, so a continuation that
                    // calls run(key) again starts a new run instead of joining
                    // this finished one.
                    inFlight.remove(key, mine);
                    mine.complete(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    inFlight.remove(key, mine);
                    mine.completeExceptionally(e);
                } catch (Throwable t) {
                    inFlight.remove(key, mine);
                    mine.completeExceptionally(t);
                } finally {
                    if (acquired) permits.release();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(e);
        }
        return mine;
    }

    /** Whether a run for this key has started or is queued for a permit. */
    public boolean isRunning(String key) {
        return inFlight.containsKey(key);
    }
}
