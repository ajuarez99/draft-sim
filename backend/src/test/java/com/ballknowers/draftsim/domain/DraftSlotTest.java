package com.ballknowers.draftsim.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DraftSlotTest {

    @Test
    void snakeOrderReversesOnEvenRounds() {
        int teams = 14;
        // round 1 runs 1..14
        assertEquals(1, DraftSlot.slot(1, teams));
        assertEquals(14, DraftSlot.slot(14, teams));
        // round 2 runs 14..1
        assertEquals(14, DraftSlot.slot(15, teams));
        assertEquals(1, DraftSlot.slot(28, teams));
        // round 3 runs 1..14 again
        assertEquals(1, DraftSlot.slot(29, teams));
    }

    @Test
    void roundsAreOneIndexed() {
        assertEquals(1, DraftSlot.round(1, 14));
        assertEquals(1, DraftSlot.round(14, 14));
        assertEquals(2, DraftSlot.round(15, 14));
        assertEquals(15, DraftSlot.round(210, 14));
    }

    @Test
    void slot11In14TeamDraftGetsTheExpectedPicks() {
        int[] picks = DraftSlot.picksForSlot(11, 14, 15);
        // 1.11, then the turn: 2.04 (pick 18), 3.11 (pick 39), 4.04 (pick 46) ...
        assertEquals(11, picks[0]);
        assertEquals(18, picks[1]);
        assertEquals(39, picks[2]);
        assertEquals(46, picks[3]);
        assertEquals(15, picks.length);
    }

    @Test
    void everyPickMapsBackToItsOwnSlot() {
        int teams = 12, rounds = 15;
        for (int slot = 1; slot <= teams; slot++) {
            for (int pick : DraftSlot.picksForSlot(slot, teams, rounds)) {
                assertEquals(slot, DraftSlot.slot(pick, teams),
                        "pick " + pick + " should belong to slot " + slot);
            }
        }
    }

    @Test
    void everyPickNumberIsClaimedExactlyOnce() {
        int teams = 14, rounds = 15;
        boolean[] seen = new boolean[teams * rounds + 1];
        for (int slot = 1; slot <= teams; slot++) {
            for (int pick : DraftSlot.picksForSlot(slot, teams, rounds)) {
                assertFalse(seen[pick], "pick " + pick + " claimed twice");
                seen[pick] = true;
            }
        }
        for (int p = 1; p < seen.length; p++) assertTrue(seen[p], "pick " + p + " unclaimed");
    }
}
