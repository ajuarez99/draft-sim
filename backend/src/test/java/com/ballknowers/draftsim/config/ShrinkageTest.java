package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShrinkageTest {

    private final ShrinkageProperties s = new ShrinkageProperties(4.0);

    @Test
    void noHistoryCollapsesToLeagueMean() {
        assertEquals(2.0, s.shrink(50.0, 2.0, 0), 1e-9);
    }

    @Test
    void twoDraftsCarryOneThirdWeight() {
        // n/(n+k) = 2/6 = 1/3
        assertEquals(10.0 / 3.0, s.shrink(10.0, 0.0, 2), 1e-9);
    }

    @Test
    void moreHistoryMovesTowardTheManagersOwnEstimate() {
        double two = s.shrink(10.0, 0.0, 2);
        double eight = s.shrink(10.0, 0.0, 8);
        assertTrue(eight > two);
        assertTrue(eight < 10.0);
    }
}
