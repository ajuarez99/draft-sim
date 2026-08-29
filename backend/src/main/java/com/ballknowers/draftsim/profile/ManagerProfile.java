package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.domain.Position;

import java.util.Map;

/**
 * How one seat drafts, after fitted history and stated belief have been combined.
 *
 * reachBias is in pick numbers and signed so that POSITIVE MEANS REACHES:
 *
 *     reachBias = mean(boardPosition - pickNumber)
 *
 * A manager who takes a player the board puts at 50 with pick 38 has a reach bias
 * of +12. (The design doc writes this as pick_no - adp and calls positive a reach;
 * that is inverted. The sign convention here is the one the engine uses.)
 *
 * positionalTilt is a multiplicative nudge per position, centered on 1.0, and is
 * only ever fitted — the user does not set it by hand.
 *
 * unpredictability multiplies the run temperature for this seat alone.
 *
 * @param provenance whether reachBias came from data, from the user, from both,
 *                   or from nothing at all. The UI must not present these alike.
 */
public record ManagerProfile(
        long managerId,
        String displayName,
        double reachBias,
        Map<Position, Double> positionalTilt,
        double unpredictability,
        String note,
        int draftsObserved,
        int picksScored,
        Provenance provenance
) {
    public double tilt(Position pos) {
        return positionalTilt.getOrDefault(pos, 1.0);
    }

    /** The league-average drafter. Every seat with no history and no stated opinion. */
    public static ManagerProfile neutral(long managerId, String displayName) {
        return new ManagerProfile(managerId, displayName, 0.0, Map.of(), 1.0, null,
                0, 0, Provenance.NEUTRAL);
    }
}
