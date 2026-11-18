package com.ballknowers.draftsim.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three hand-set limits, on a settable clock. The numbers are guesses; these tests pin the mechanism. */
class UsageLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("1990-03-12T17:00:00Z"), ZoneOffset.UTC);
    private final UsageLimiter limiter = new UsageLimiter(clock);

    @Test
    void sixtyPassAndTheSixtyFirstDoesNot() {
        for (int i = 1; i <= UsageLimiter.PER_ADDRESS_PER_WINDOW; i++) assertTrue(limiter.permit("a"), "call " + i);
        assertFalse(limiter.permit("a"));
        assertFalse(limiter.permit("a"), "a refused call is still counted and stays refused");
        assertTrue(limiter.permit("b"), "another address has its own count");
    }

    @Test
    void aNewWindowResetsEveryAddress() {
        for (int i = 0; i < 70; i++) limiter.permit("a");
        assertFalse(limiter.permit("a"));
        clock.advance(Duration.ofSeconds(61));
        assertTrue(limiter.permit("a"));
    }

    @Test
    void theAddressCapRefusesNewAddressesAndStillServesKnownOnes() {
        assertTrue(limiter.permit("known"));
        for (int i = 0; i < UsageLimiter.MAX_TRACKED_ADDRESSES - 1; i++) limiter.permit("addr-" + i);
        assertFalse(limiter.permit("one-too-many"), "a new address past the cap is refused");
        assertFalse(limiter.permit("one-too-many"));
        assertTrue(limiter.permit("known"), "an address already tracked is still served");
        clock.advance(Duration.ofSeconds(61));
        assertTrue(limiter.permit("one-too-many"), "the window turned, so the cap is clear");
    }

    @Test
    void aMissingAddressSharesOneBucketInsteadOfBypassingTheLimit() {
        for (int i = 0; i < UsageLimiter.PER_ADDRESS_PER_WINDOW; i++) limiter.permit(null);
        assertFalse(limiter.permit(""));
        assertFalse(limiter.permit("   "));
    }

    @Test
    void theGlobalWriteCeilingHoldsWhateverTheAddressesAre() {
        int granted = 0;
        for (int i = 0; i < UsageLimiter.GLOBAL_WRITES_PER_WINDOW + 50; i++) if (limiter.tryWrite()) granted++;
        assertEquals(UsageLimiter.GLOBAL_WRITES_PER_WINDOW, granted);
        clock.advance(Duration.ofSeconds(61));
        assertTrue(limiter.tryWrite(), "the ceiling resets with the window");
    }
}
