package com.ballknowers.draftsim.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cross-request cap on Monte Carlo runs (claude/audit-2026-09-28/05).
 *
 * One run already fans out a virtual thread per iteration across every core,
 * so a second run does not wait its turn, it competes. This bounds how many
 * run at once.
 *
 * <p>The real load case is a live draft: about a dozen managers each have the
 * live room open, and every pick makes every tab resimulate at nearly the same
 * moment. An instant refusal would 429 most of them on every pick, so a caller
 * waits a short, bounded time for a permit ({@code draftsim.sims.wait-ms},
 * default 3000 -- a guess, not measured). The number of callers allowed to wait
 * at once is also capped at {@code permits x 8, enough for a 12-manager room at the minimum size of 2} (the 8 is a guess too); past
 * that the answer is an immediate {@link SimulationBusyException}, so a flood
 * cannot pile up waiting threads.
 *
 * <p>Size defaults to {@code max(2, cores / 4)}. That is a GUESS, not a
 * measured capacity -- production's core count and burst behaviour are
 * unknown. {@code draftsim.sims.max-concurrent} overrides it (also the way to
 * force a 429 locally).
 *
 * <p>A signed-in caller holds at most one lease PER DRAFT: a new request from
 * the same identity for the same draft cancels the old run and inherits its
 * permit without waiting, because the UI's own resim (restart) would otherwise
 * 429 the user against themselves. Another draft in another tab is not touched.
 * A caller with no identity is never replaced -- an absent identity is no
 * claim, so it can neither own nor displace anything.
 */
@Component
public class SimulationPermits {

    private static final int WAITING_FACTOR = 8;   // guess

    private final Semaphore permits;
    private final int size;
    private final long waitMillis;
    private final int maxWaiting;
    private final AtomicInteger waiting = new AtomicInteger();
    private final Object lock = new Object();
    private final Map<String, Lease> byKey = new HashMap<>();

    @Autowired
    public SimulationPermits(@Value("${draftsim.sims.max-concurrent:0}") int configured,
                             @Value("${draftsim.sims.wait-ms:3000}") long waitMillis) {
        this(configured, waitMillis, WAITING_FACTOR);
    }

    SimulationPermits(int configured, long waitMillis, int waitingFactor) {
        this.size = configured > 0
                ? configured
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        this.permits = new Semaphore(size);
        this.waitMillis = waitMillis;
        this.maxWaiting = size * waitingFactor;
    }

    /** No waiting; for tests that want the instant-refusal behaviour. */
    SimulationPermits(int configured) {
        this(configured, 0, WAITING_FACTOR);
    }

    public int size() {
        return size;
    }

    public int available() {
        return permits.availablePermits();
    }

    public int waiting() {
        return waiting.get();
    }

    public Lease acquire(String identity) {
        return acquire(identity, null);
    }

    /** @throws SimulationBusyException when no permit frees up in time, or too many are already waiting. */
    public Lease acquire(String identity, String draftId) {
        String who = identity == null || identity.isBlank() ? null : identity.trim();
        String key = who == null ? null : who + "|" + (draftId == null ? "" : draftId.trim());
        synchronized (lock) {
            Lease old = key == null ? null : byKey.get(key);
            if (old != null) {
                // Hand the permit over rather than releasing it: release then
                // re-acquire would let a stranger take it in between, and the
                // old run only notices its flag on its next iteration.
                old.cancel();
                old.ownsPermit = false;
                return register(key, new Lease(key));
            }
            if (permits.tryAcquire()) return register(key, new Lease(key));
        }
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet();
            throw new SimulationBusyException();
        }
        try {
            if (waitMillis <= 0 || !permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new SimulationBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulationBusyException();
        } finally {
            waiting.decrementAndGet();
        }
        synchronized (lock) {
            // Someone with the same key may have registered while we waited; we
            // hold our own permit, so just cancel theirs (they release their own).
            Lease raced = key == null ? null : byKey.get(key);
            if (raced != null) raced.cancel();
            return register(key, new Lease(key));
        }
    }

    private Lease register(String key, Lease lease) {   // call under lock
        if (key != null) byKey.put(key, lease);
        return lease;
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean ownsPermit = true;     // guarded by lock
        private boolean closed;                // guarded by lock

        private Lease(String key) {
            this.key = key;
        }

        public boolean cancelled() {
            return cancelled.get();
        }

        public void cancel() {
            cancelled.set(true);
        }

        /** Idempotent. Always call from a finally. */
        @Override
        public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                if (key != null && byKey.get(key) == this) byKey.remove(key);
                if (ownsPermit) permits.release();
            }
        }
    }
}
