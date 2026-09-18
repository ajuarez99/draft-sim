package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.domain.*;

import java.util.List;
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

    /** One player this sport's rules would start, and the slot he fills. */
    record Assigned(BoardEntry entry, String slot, double value) {}

    /**
     * Which players the seat would actually start, valued by {@code valueOf}.
     *
     * The reporting form of {@link #startingLineupValue}: same rule, but it
     * names the starters instead of only totalling them, and it takes the value
     * function rather than assuming {@link #value}. claude/league-analysis.md
     * needs both -- a roster's projected points broken out by position group is
     * this list, grouped.
     *
     * <p><b>This values a lineup with whatever function it is handed, and must
     * not assume that function is a projection.</b> It previously carried a
     * throwing default, justified on the grounds that a sport without a
     * projection source had nothing to value a lineup with. That reasoning was
     * right about projections and wrong as a blanket rule: a backward-looking
     * caller passes points a player has <i>already scored</i>, which Sleeper
     * reports for every sport it serves. The default therefore locked
     * basketball out of views whose data it already had, and it was
     * inheritable -- a new sport got a runtime failure rather than a compile
     * error. Both problems go away by making this abstract, so every sport
     * must answer (specs/004-ffwrapped-feature-parity, research R4).
     *
     * <p>The original concern still stands for <i>callers</i>: do not pass
     * {@link #value} where a projection is meant. A draft-board number is not
     * a forecast, and a caller that needs one and has none should decline to
     * answer rather than substitute the other.
     */
    List<Assigned> startingLineup(RosterState roster, LeagueSettings settings,
                                  ToDoubleFunction<BoardEntry> valueOf);

    /**
     * Hard gate: some positions are simply not taken until the draft is nearly
     * over. Expressed in rounds REMAINING rather than rounds elapsed, so the
     * gate means the same thing in a league of any length -- "kickers go in the
     * last few rounds" is roster-filling behaviour, and a fixed round number
     * only encodes it for the one league length it was written against.
     *
     * @param lineup this seat's {@link #prepareLineup} result, computed once
     *               per pick ahead of the candidate filter this gates (see
     *               PickDecider.choose) -- so an implementation whose gate
     *               depends on roster shape (the Sleeper eligibility lock,
     *               claude/multi-sport-and-rebrand.md §3b) can read it in O(1)
     *               without recomputing anything. {@code FootballRules} does
     *               not need roster shape for its gate and ignores this.
     */
    boolean isDraftable(BoardEntry entry, Object lineup, int round, int totalRounds);

    boolean isEligible(Player player, String rosterSlot);
}
