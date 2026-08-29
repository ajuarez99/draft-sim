package com.ballknowers.draftsim.domain;

import java.util.List;

public record Player(
        long id,
        Sport sport,
        String sleeperId,
        String name,
        List<Position> positions,
        String team,
        String status,
        String injuryStatus,
        Integer age,
        Integer yearsExp
) {
    /** The position the engine treats this player as. Multi-eligibility is a basketball problem. */
    public Position primary() {
        return positions.isEmpty() ? Position.WR : positions.getFirst();
    }
}
