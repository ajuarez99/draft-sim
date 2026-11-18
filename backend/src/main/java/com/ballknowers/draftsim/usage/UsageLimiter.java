package com.ballknowers.draftsim.usage;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abuse limits for the public usage send (spec 076). All three numbers below are HAND-SET GUESSES. None was
 * measured against real traffic; they exist so one noisy client, or a flood from many, cannot fill the table or
 * pin the database pool. Retune them when real numbers exist, and say so where you do.
 *
 * <p>Fixed one-minute window. Each address is counted on EVERY call that reaches {@link #permit}, accepted or
 * not, so a client that hammers junk is limited just as one that hammers valid sends. The per-address limit is
 * best effort: the address is whatever the caller derived (X-Real-IP, else the socket address), and behind an
 * unexpected proxy that could be one shared address or an attacker-chosen one. The global write ceiling is what
 * actually bounds the damage whatever the addresses are.
 */
@Component
public class UsageLimiter {

    /** HAND-SET GUESS, not measured: sends one address may make per window. A page view plus a few actions per minute is far below it. */
    static final int PER_ADDRESS_PER_WINDOW = 60;
    /** HAND-SET GUESS, not measured: addresses tracked per window; past it new addresses are refused until the window turns. Bounds memory. */
    static final int MAX_TRACKED_ADDRESSES = 10_000;
    /** HAND-SET GUESS, not measured: database writes this service will make per window, from all addresses together. Bounds pool use. */
    static final int GLOBAL_WRITES_PER_WINDOW = 600;

    private static final long WINDOW_MILLIS = 60_000L;

    private final Clock clock;
    private final AtomicLong window = new AtomicLong(Long.MIN_VALUE);
    private final ConcurrentHashMap<String, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();

    public UsageLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts this call and says whether the address is still inside its limit. */
    public boolean permit(String address) {
        roll();
        String key = (address == null || address.isBlank()) ? "?" : address;
        AtomicInteger count = perAddress.get(key);
        if (count == null) {
            if (perAddress.size() >= MAX_TRACKED_ADDRESSES) return false;
            count = perAddress.computeIfAbsent(key, k -> new AtomicInteger());
        }
        return count.incrementAndGet() <= PER_ADDRESS_PER_WINDOW;
    }

    /** Takes one of this window's global database writes; false when the ceiling is reached. */
    public boolean tryWrite() {
        roll();
        return writes.incrementAndGet() <= GLOBAL_WRITES_PER_WINDOW;
    }

    private void roll() {
        long now = clock.millis() / WINDOW_MILLIS;
        long seen = window.get();
        if (now != seen && window.compareAndSet(seen, now)) {
            perAddress.clear();
            writes.set(0);
        }
    }
}
