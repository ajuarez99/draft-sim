package com.ballknowers.draftsim.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationPermitsTest {

    @Test
    void aPermitIsTakenAndGivenBack() {
        SimulationPermits permits = new SimulationPermits(2);
        SimulationPermits.Lease a = permits.acquire("u1");
        assertEquals(1, permits.available());
        a.close();
        assertEquals(2, permits.available());
    }

    @Test
    void closeIsIdempotentSoAFinallyPlusACallbackCannotOverRelease() {
        SimulationPermits permits = new SimulationPermits(1);
        SimulationPermits.Lease a = permits.acquire(null);
        a.close();
        a.close();
        assertEquals(1, permits.available());
    }

    @Test
    void aPermitComesBackWhenTheRunThrows() {
        SimulationPermits permits = new SimulationPermits(1);
        assertThrows(IllegalStateException.class, () -> {
            try (SimulationPermits.Lease ignored = permits.acquire("u1")) {
                throw new IllegalStateException("boom");
            }
        });
        assertEquals(1, permits.available());
    }

    @Test
    void aCancelledLeaseStillReleasesOnClose() {
        SimulationPermits permits = new SimulationPermits(1);
        SimulationPermits.Lease a = permits.acquire(null);
        a.cancel();
        assertTrue(a.cancelled());
        a.close();
        assertEquals(1, permits.available());
    }

    @Test
    void whenEveryPermitIsTakenTheNextCallerIsRefusedImmediately() {
        SimulationPermits permits = new SimulationPermits(1);
        SimulationPermits.Lease a = permits.acquire("u1");
        assertThrows(SimulationBusyException.class, () -> permits.acquire("u2"));
        assertThrows(SimulationBusyException.class, () -> permits.acquire(null));
        a.close();
        permits.acquire("u2").close();
    }

    @Test
    void aNewRequestFromTheSameIdentityReplacesTheOldRunInsteadOfBeingRefused() {
        SimulationPermits permits = new SimulationPermits(1);
        SimulationPermits.Lease old = permits.acquire("u1");
        SimulationPermits.Lease next = permits.acquire("u1");

        assertTrue(old.cancelled());
        assertFalse(next.cancelled());
        assertEquals(0, permits.available());

        // The old run winding down must not free the permit the new run holds...
        old.close();
        assertEquals(0, permits.available());
        assertThrows(SimulationBusyException.class, () -> permits.acquire("u2"));
        // ...and the new run's close frees it exactly once.
        next.close();
        assertEquals(1, permits.available());
    }

    @Test
    void replacementChainsAndStillReleasesOnce() {
        SimulationPermits permits = new SimulationPermits(2);
        SimulationPermits.Lease a = permits.acquire(" u1 ");
        SimulationPermits.Lease b = permits.acquire("u1");
        SimulationPermits.Lease c = permits.acquire("u1");
        assertTrue(a.cancelled());
        assertTrue(b.cancelled());
        assertEquals(1, permits.available());
        c.close();
        b.close();
        a.close();
        assertEquals(2, permits.available());
    }

    @Test
    void anAnonymousCallerNeitherOwnsNorDisplacesAnything() {
        SimulationPermits permits = new SimulationPermits(2);
        SimulationPermits.Lease a = permits.acquire(null);
        SimulationPermits.Lease b = permits.acquire("  ");
        assertFalse(a.cancelled());
        assertEquals(0, permits.available());
        assertThrows(SimulationBusyException.class, () -> permits.acquire(""));
        a.close();
        b.close();
        assertEquals(2, permits.available());
    }

    @Test
    void theDefaultSizeIsAtLeastOne() {
        assertTrue(new SimulationPermits(0).size() >= 2);
    }

    @Test
    void sameIdentityAndDraftReplacesButADifferentDraftDoesNot() {
        SimulationPermits permits = new SimulationPermits(2);
        SimulationPermits.Lease a1 = permits.acquire("u1", "draftA");
        SimulationPermits.Lease b = permits.acquire("u1", "draftB");
        assertFalse(a1.cancelled(), "a run on another draft must not cancel this one");
        assertEquals(0, permits.available());

        SimulationPermits.Lease a2 = permits.acquire("u1", "draftA");
        assertTrue(a1.cancelled());
        assertFalse(b.cancelled());
        assertFalse(a2.cancelled());
        assertEquals(0, permits.available());
        a1.close();
        a2.close();
        b.close();
        assertEquals(2, permits.available());
    }

    @Test
    void aCallerWaitsForAPermitThatFreesInTime() throws Exception {
        SimulationPermits permits = new SimulationPermits(1, 2000, 8);
        SimulationPermits.Lease held = permits.acquire("u1", "d");
        Thread t = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            held.close();
        });
        t.start();
        SimulationPermits.Lease got = permits.acquire("u2", "d");
        assertEquals(0, permits.available());
        got.close();
        t.join();
        assertEquals(1, permits.available());
        assertEquals(0, permits.waiting());
    }

    @Test
    void aWaiterGivesUpAfterTheWindow() {
        SimulationPermits permits = new SimulationPermits(1, 100, 8);
        SimulationPermits.Lease held = permits.acquire("u1", "d");
        long t = System.nanoTime();
        assertThrows(SimulationBusyException.class, () -> permits.acquire("u2", "d"));
        assertTrue((System.nanoTime() - t) / 1_000_000 >= 90);
        assertEquals(0, permits.waiting());
        held.close();
    }

    @Test
    void requestsPastTheWaitingCapAreRefusedAtOnce() throws Exception {
        // 1 permit x factor 2 = at most 2 waiters; window is long so any waiter would sit.
        SimulationPermits permits = new SimulationPermits(1, 30_000, 2);
        SimulationPermits.Lease held = permits.acquire("u0", "d");
        java.util.List<Thread> waiters = new java.util.ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String who = "w" + i;
            Thread t = new Thread(() -> { try { permits.acquire(who, "d").close(); } catch (RuntimeException ignored) { } });
            t.start();
            waiters.add(t);
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (permits.waiting() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(2, permits.waiting());

        long t0 = System.nanoTime();
        assertThrows(SimulationBusyException.class, () -> permits.acquire("flood", "d"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500, "must refuse without waiting");
        assertEquals(2, permits.waiting());

        held.close();
        for (Thread w : waiters) w.join(5000);
        assertEquals(0, permits.waiting());
        assertEquals(1, permits.available());
    }
}
