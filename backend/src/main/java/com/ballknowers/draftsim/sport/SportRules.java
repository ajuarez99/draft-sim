package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.domain.*;

/**
 * The one seam between shared machinery and sport-specific behavior.
 * Only FootballRules exists in v1. Basketball is deferred, but the interface
 * is here so adding it is an implementation, not a refactor.
 */
public interface SportRules {

    Sport sport();

    /**
     * How much of this player's value would actually reach the seat's starting
     * lineup, in [benchFloor, 1]. 1.0 means he starts immediately at full value;
     * benchFloor means he is pure depth.
     */
    double rosterNeed(RosterState roster, BoardEntry candidate, LeagueSettings settings);

    /** Board value of a player, decreasing in board position. */
    double value(BoardEntry entry);

    /** Hard gate: some positions are simply not taken before a given round. */
    boolean isDraftable(BoardEntry entry, int round);

    boolean isEligible(Player player, String rosterSlot);
}
