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

    /**
     * First-season player: Sleeper's {@code years_exp} is exactly 0. Null is never a rookie
     * (every NFL team defense has null experience). The one definition, shared by the league
     * home's Rookie watch and the draft rooms' rookie mark (specs/014 research R4).
     * <p>{@code years_exp} is TODAY's value, so this is only right for the sport's current
     * season: asked about a past season it mislabels (a 2025 rookie reads 1 in 2026).
     */
    public boolean isRookie() {
        return yearsExp != null && yearsExp == 0;
    }
}
