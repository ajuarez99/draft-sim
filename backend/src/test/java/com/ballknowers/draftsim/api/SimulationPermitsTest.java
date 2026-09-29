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
        assertTrue(new SimulationPermits(0).size() >= 1);
    }
}
