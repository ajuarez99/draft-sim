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
     * <p>Unimplemented for a sport until that sport has a projection source to
     * value a lineup with. This throws rather than quietly falling back to
     * {@link #value}, which would answer a projection question with a draft-
     * board answer and look like it worked.
     */
    default List<Assigned> startingLineup(RosterState roster, LeagueSettings settings,
                                          ToDoubleFunction<BoardEntry> valueOf) {
        throw new UnsupportedOperationException(
                sport().code() + " has no startingLineup: see claude/league-analysis.md's non-goals. "
                        + "Sleeper's projection stat keys (pts_ppr and friends) are football's, and "
                        + "nothing values a basketball lineup in points yet.");
    }

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
