package com.ballknowers.draftsim.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cross-request cap on Monte Carlo runs (claude/audit-2026-09-28/05).
 *
 * One run already fans out a virtual thread per iteration across every core,
 * so a second run does not wait its turn, it competes. This bounds how many
 * run at once. It never queues: {@link #acquire} answers immediately, with a
 * lease or a {@link SimulationBusyException}.
 *
 * <p>Size defaults to {@code max(1, cores / 4)}. That is a GUESS, not a
 * measured capacity -- production's core count and burst behaviour are
 * unknown. {@code draftsim.sims.max-concurrent} overrides it (also the way to
 * force a 429 locally, with a value of 1).
 *
 * <p>A signed-in caller holds at most one lease: a new request from the same
 * identity cancels the old run and inherits its permit instead of being
 * refused, because the UI's own resim (restart) would otherwise 429 the user
 * against themselves. A caller with no identity is never replaced -- an absent
 * identity is no claim, so it can neither own nor displace anything.
 */
@Component
public class SimulationPermits {

    private final Semaphore permits;
    private final int size;
    private final Object lock = new Object();
    private final Map<String, Lease> byIdentity = new HashMap<>();

    public SimulationPermits(@Value("${draftsim.sims.max-concurrent:0}") int configured) {
        this.size = configured > 0
                ? configured
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        this.permits = new Semaphore(size);
    }

    public int size() {
        return size;
    }

    public int available() {
        return permits.availablePermits();
    }

    /** @throws SimulationBusyException when no permit is free and this is not a replacement. */
    public Lease acquire(String identity) {
        String key = identity == null || identity.isBlank() ? null : identity.trim();
        synchronized (lock) {
            Lease old = key == null ? null : byIdentity.get(key);
            if (old != null) {
                // Hand the permit over rather than releasing it: release then
                // re-acquire would let a stranger take it in between, and the
                // old run only notices its flag on its next iteration.
                old.cancel();
                old.ownsPermit = false;
            } else if (!permits.tryAcquire()) {
                throw new SimulationBusyException();
            }
            Lease lease = new Lease(key);
            if (key != null) byIdentity.put(key, lease);
            return lease;
        }
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
                if (key != null && byIdentity.get(key) == this) byIdentity.remove(key);
                if (ownsPermit) permits.release();
            }
        }
    }
}
