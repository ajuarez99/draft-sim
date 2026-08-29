package com.ballknowers.draftsim.domain;

/**
 * One player's position on the derived board.
 *
 * adp here is a pick number, not a rank: "the pick at which this player is
 * typically taken". positionalRank is their rank within their own position,
 * which is what the need model uses as a value proxy.
 */
public record BoardEntry(
        Player player,
        double adp,
        int positionalRank
) {
    public Position position() {
        return player.primary();
    }
}
