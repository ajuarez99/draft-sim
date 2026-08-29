package com.ballknowers.draftsim.domain;

/** Snake order helpers. Pick numbers and slots are both 1-indexed. */
public final class DraftSlot {

    private DraftSlot() {}

    public static int round(int pickNo, int teams) {
        return ((pickNo - 1) / teams) + 1;
    }

    public static int slot(int pickNo, int teams) {
        int round = round(pickNo, teams);
        int indexInRound = ((pickNo - 1) % teams) + 1;
        // Odd rounds run 1..teams, even rounds reverse.
        return (round % 2 == 1) ? indexInRound : (teams - indexInRound + 1);
    }

    /** The pick numbers belonging to one slot across the whole draft. */
    public static int[] picksForSlot(int slot, int teams, int rounds) {
        int[] out = new int[rounds];
        for (int r = 1; r <= rounds; r++) {
            int indexInRound = (r % 2 == 1) ? slot : (teams - slot + 1);
            out[r - 1] = (r - 1) * teams + indexInRound;
        }
        return out;
    }
}
