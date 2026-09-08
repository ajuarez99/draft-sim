package com.ballknowers.draftsim.domain;

/**
 * Snake order helpers. Pick numbers and slots are both 1-indexed.
 *
 * Every method comes in two forms: a plain-snake overload (unchanged since
 * before multi-sport-and-rebrand.md Phase 5, kept for the many football-only
 * and sport-agnostic callers -- mock drafts, the live poller, the simulator's
 * hot loop) and a {@code reversalRound}-aware overload for basketball, whose
 * 2026 league draft is the one real case this project needs it for. The
 * plain overloads are defined as the reversal-aware ones called with
 * {@code reversalRound = 0}, so there is exactly one place the math lives.
 */
public final class DraftSlot {

    private DraftSlot() {}

    public static int round(int pickNo, int teams) {
        return ((pickNo - 1) / teams) + 1;
    }

    /**
     * Whether this round runs low-to-high slot order (1..teams) rather than
     * high-to-low. Plain snake alternates every round: odd rounds forward,
     * even rounds reverse. {@code reversalRound} changes what happens from
     * that round on: normal alternating parity holds for every round
     * strictly before it, and from {@code reversalRound} onward the parity
     * is flipped relative to plain snake -- not reset to always-forward or
     * always-reverse. So at {@code reversalRound = 3}: R1 forward and R2
     * reverse exactly as plain snake would run them (both < 3); R3 would be
     * forward under plain snake (odd) but is flipped to reverse; R4 would be
     * reverse under plain snake (even) but is flipped to forward; and so on
     * for every later round. {@code reversalRound <= 0} means "never
     * flips", i.e. plain snake for the whole draft.
     *
     * ASSUMPTION, not verified against real data. Both NBA drafts this
     * project's Sleeper account has ever completed are
     * {@code reversal_round: 0}; the only draft using
     * {@code reversal_round: 3} -- the 2026 Ball Knowers NBA draft, the one
     * this project exists to simulate -- is still {@code pre_draft} with no
     * picks, so this reading of Sleeper's field has never been checked
     * against an observed {@code draft_slot}. See
     * claude/multi-sport-and-rebrand.md, "Verifying reversal_round: 3
     * before writing that change". {@link com.ballknowers.draftsim.domain.DraftSlotReversalRoundTest}
     * pins this exact round-3/round-4 reading explicitly; if real 2026 data
     * ever contradicts it, that test is what should start failing -- this
     * comment is the tripwire that says why, not a claim that the reading
     * is correct.
     */
    static boolean isForward(int round, int reversalRound) {
        boolean plainSnakeForward = round % 2 == 1;
        if (reversalRound <= 0 || round < reversalRound) return plainSnakeForward;
        return !plainSnakeForward;
    }

    /** Plain snake -- {@code reversalRound = 0}. See the class comment. */
    public static int slot(int pickNo, int teams) {
        return slot(pickNo, teams, 0);
    }

    public static int slot(int pickNo, int teams, int reversalRound) {
        int round = round(pickNo, teams);
        int indexInRound = ((pickNo - 1) % teams) + 1;
        return isForward(round, reversalRound) ? indexInRound : (teams - indexInRound + 1);
    }

    /** Plain snake -- {@code reversalRound = 0}. See the class comment. */
    public static int[] picksForSlot(int slot, int teams, int rounds) {
        return picksForSlot(slot, teams, rounds, 0);
    }

    /** The pick numbers belonging to one slot across the whole draft. */
    public static int[] picksForSlot(int slot, int teams, int rounds, int reversalRound) {
        int[] out = new int[rounds];
        for (int r = 1; r <= rounds; r++) {
            int indexInRound = isForward(r, reversalRound) ? slot : (teams - slot + 1);
            out[r - 1] = (r - 1) * teams + indexInRound;
        }
        return out;
    }
}
