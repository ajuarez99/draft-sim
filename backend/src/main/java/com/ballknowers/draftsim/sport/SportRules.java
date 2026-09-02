package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.domain.*;

import java.util.function.ToDoubleFunction;

/**
 * The one seam between shared machinery and sport-specific behavior.
 * Only FootballRules exists in v1. Basketball is deferred, but the interface
 * is here so adding it is an implementation, not a refactor.
 */
public interface SportRules {

    Sport sport();

    /**
     * Precomputes whatever a sport's {@link #rosterNeed} needs to score every
     * candidate at one pick against {@code roster} in O(1) each, instead of
     * every candidate separately recomputing roster-wide structure that does
     * not depend on which candidate is being scored. Opaque to callers:
     * prepare it once per pick and pass the same instance to every
     * {@link #rosterNeed} call for that pick.
     *
     * @param valueOf caller-supplied {@link #value}, so a caller holding a
     *                faster (e.g. precomputed/indexed) value lookup for this
     *                request's board can use it instead of this instance's own.
     */
    Object prepareLineup(RosterState roster, LeagueSettings settings, ToDoubleFunction<BoardEntry> valueOf);

    /**
     * How much of this player's value would actually reach the seat's starting
     * lineup, in [benchFloor, 1]. 1.0 means he starts immediately at full value;
     * benchFloor means he is pure depth.
     */
    double rosterNeed(BoardEntry candidate, Object lineup);

    /** Expected value of the starting lineup {@code lineup} was prepared from. */
    double lineupValue(Object lineup);

    /** Board value of a player, decreasing in board position. */
    double value(BoardEntry entry);

    /** Expected value of the seat's starting lineup as currently rostered. */
    double startingLineupValue(RosterState roster, LeagueSettings settings);

    /** Hard gate: some positions are simply not taken before a given round. */
    boolean isDraftable(BoardEntry entry, int round);

    boolean isEligible(Player player, String rosterSlot);
}
