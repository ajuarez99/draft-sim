package com.ballknowers.draftsim.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the ASSUMED reading of Sleeper's {@code reversal_round} explicitly,
 * per claude/multi-sport-and-rebrand.md's "Verifying reversal_round: 3 before
 * writing that change".
 *
 * No completed draft anywhere on this project's Sleeper account has ever used
 * a nonzero {@code reversal_round} -- the only one that does (the 2026 Ball
 * Knowers NBA draft, {@code reversal_round: 3}) is still {@code pre_draft}
 * with no picks, so the reading below cannot be checked against a real
 * observed {@code draft_slot} the way {@link DraftSlotTest
 * #reversalRoundZeroReproducesAllObservedSlotsOfTheReal2025NbaDraft} checks
 * {@code reversal_round: 0}. This test exists so that if real 2026 data ever
 * contradicts the assumption, the failure is THIS test -- named for exactly
 * what it assumes -- rather than a silently misordered simulation.
 *
 * The assumed reading, spelled out (see also the comment on
 * {@link DraftSlot#isForward}): normal alternating snake parity holds for
 * every round strictly before {@code reversalRound}, and from
 * {@code reversalRound} on, the parity that plain snake would have used is
 * flipped -- not reset to always-forward or always-reverse. At
 * {@code reversalRound = 3} that reads as R1 forward, R2 reverse (both
 * unchanged from plain snake), R3 reverse (plain snake would run it forward,
 * since round 3 is odd), R4 forward (plain snake would run it reverse).
 */
class DraftSlotReversalRoundTest {

    private static final int TEAMS = 4;
    private static final int REVERSAL_ROUND = 3;

    @Test
    void roundsBeforeReversalRoundKeepPlainSnakeParity() {
        // R1 forward: 1,2,3,4 (unchanged from reversal_round=0)
        assertEquals(1, DraftSlot.slot(1, TEAMS, REVERSAL_ROUND));
        assertEquals(2, DraftSlot.slot(2, TEAMS, REVERSAL_ROUND));
        assertEquals(3, DraftSlot.slot(3, TEAMS, REVERSAL_ROUND));
        assertEquals(4, DraftSlot.slot(4, TEAMS, REVERSAL_ROUND));
        // R2 reverse: 4,3,2,1 (unchanged from reversal_round=0)
        assertEquals(4, DraftSlot.slot(5, TEAMS, REVERSAL_ROUND));
        assertEquals(3, DraftSlot.slot(6, TEAMS, REVERSAL_ROUND));
        assertEquals(2, DraftSlot.slot(7, TEAMS, REVERSAL_ROUND));
        assertEquals(1, DraftSlot.slot(8, TEAMS, REVERSAL_ROUND));
    }

    @Test
    void roundThreeFlipsToReverseInsteadOfThePlainSnakeForward() {
        // Plain snake (reversal_round=0) would run round 3 forward, since it's
        // odd: 1,2,3,4 at picks 9-12. The assumed reading flips that to
        // reverse: 4,3,2,1 -- back-to-back with round 2's own reverse, an
        // 8-pick run from the same slot ordering (picks 5-12: 4,3,2,1,4,3,2,1).
        assertEquals(4, DraftSlot.slot(9, TEAMS, REVERSAL_ROUND), "round 3, pick 1 of 4");
        assertEquals(3, DraftSlot.slot(10, TEAMS, REVERSAL_ROUND), "round 3, pick 2 of 4");
        assertEquals(2, DraftSlot.slot(11, TEAMS, REVERSAL_ROUND), "round 3, pick 3 of 4");
        assertEquals(1, DraftSlot.slot(12, TEAMS, REVERSAL_ROUND), "round 3, pick 4 of 4");

        // Confirm it really is a flip, not a coincidence of this team count --
        // plain snake disagrees with the assumed reading on every one of these.
        for (int pickNo = 9; pickNo <= 12; pickNo++) {
            assertNotEquals(DraftSlot.slot(pickNo, TEAMS), DraftSlot.slot(pickNo, TEAMS, REVERSAL_ROUND),
                    "pick " + pickNo + " should differ between plain snake and the reversal_round=3 reading");
        }
    }

    @Test
    void roundFourFlipsToForwardInsteadOfThePlainSnakeReverse() {
        // Plain snake would run round 4 reverse (even): 4,3,2,1 at picks 13-16.
        // The assumed reading flips that to forward: 1,2,3,4.
        assertEquals(1, DraftSlot.slot(13, TEAMS, REVERSAL_ROUND), "round 4, pick 1 of 4");
        assertEquals(2, DraftSlot.slot(14, TEAMS, REVERSAL_ROUND), "round 4, pick 2 of 4");
        assertEquals(3, DraftSlot.slot(15, TEAMS, REVERSAL_ROUND), "round 4, pick 3 of 4");
        assertEquals(4, DraftSlot.slot(16, TEAMS, REVERSAL_ROUND), "round 4, pick 4 of 4");
    }

    @Test
    void picksForSlotAgreesWithSlotUnderTheAssumedReading() {
        // Slot 1's own pick numbers across 4 rounds, hand-traced above:
        // R1 pick 1, R2 pick 8 (reverse), R3 pick 12 (still reverse -- the
        // flip), R4 pick 13 (forward -- the flip).
        int[] picks = DraftSlot.picksForSlot(1, TEAMS, 4, REVERSAL_ROUND);
        assertArrayEquals(new int[] {1, 8, 12, 13}, picks);

        for (int r = 0; r < picks.length; r++) {
            assertEquals(1, DraftSlot.slot(picks[r], TEAMS, REVERSAL_ROUND),
                    "pick " + picks[r] + " should map back to slot 1");
        }
    }

    @Test
    void reversalRoundZeroOrNegativeIsExactlyPlainSnake() {
        for (int pickNo = 1; pickNo <= TEAMS * 6; pickNo++) {
            int plain = DraftSlot.slot(pickNo, TEAMS);
            assertEquals(plain, DraftSlot.slot(pickNo, TEAMS, 0), "reversalRound=0, pick " + pickNo);
            assertEquals(plain, DraftSlot.slot(pickNo, TEAMS, -1), "reversalRound=-1, pick " + pickNo);
        }
    }
}
