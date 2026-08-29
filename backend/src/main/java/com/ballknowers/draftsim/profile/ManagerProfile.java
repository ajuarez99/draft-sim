package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.domain.Position;

import java.util.Map;

/**
 * Two parameters per manager, both shrunk hard toward the league mean.
 * With one or two drafts of history, anything richer is fitting noise.
 *
 * reachBias is in pick numbers and signed so that POSITIVE MEANS REACHES:
 *
 *     reachBias = mean(boardPosition - pickNumber)
 *
 * A manager who takes a player the board puts at 50 with pick 38 has a reach
 * bias of +12. (The design doc writes this as pick_no - adp and calls positive
 * a reach; that is inverted. The sign convention here is the one the engine
 * uses: reach bias is added to valueDelta, so a positive value makes a player
 * look better sooner.)
 *
 * positionalTilt is a multiplicative nudge per position, centered on 1.0.
 * 1.15 for RB means this manager takes RB about 15% more often than the room
 * does at the same point in the draft.
 */
public record ManagerProfile(
        long managerId,
        String displayName,
        double reachBias,
        Map<Position, Double> positionalTilt,
        int draftsObserved,
        int picksScored
) {
    public double tilt(Position pos) {
        return positionalTilt.getOrDefault(pos, 1.0);
    }

    /** The league-average drafter. Used for every seat with no usable history. */
    public static ManagerProfile neutral(long managerId, String displayName) {
        return new ManagerProfile(managerId, displayName, 0.0, Map.of(), 0, 0);
    }
}
