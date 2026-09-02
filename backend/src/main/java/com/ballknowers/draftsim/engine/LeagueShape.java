package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.LeagueSettings;

import java.util.List;
import java.util.Set;

/**
 * A league to simulate, described directly rather than looked up.
 *
 * {@link com.ballknowers.draftsim.engine.SimulationService} can only build a
 * {@link com.ballknowers.draftsim.domain.LeagueSettings} from a real
 * {@code draft} + {@code league} row. Two planned features need the other
 * direction — a league described by a request payload with no DB row behind it:
 * the ad-hoc league-size feature (claude/next-features-roadmap.md, feature A)
 * and the interactive mock room (feature C), which starts a session and can
 * re-run an outlook against the same shape. §2(b) of that doc says build it
 * once, here, so both call the identical code.
 *
 * <h2>Roster template</h2>
 * Fixed per v1, not caller-supplied (§3.3). The template is fantasy(heart)'s —
 * QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 bench — which is also every other
 * league in this project's "League facts". Stacking an untested dimension
 * (arbitrary roster shapes) on top of the normalization change in the same
 * release buys nothing: no league anyone here drafts in has a different one.
 * A roster editor is deferred, not designed-out — the field is already a
 * {@code List<String>} because Sleeper's own is.
 */
public record LeagueShape(int teams, int rounds, List<String> rosterPositions, double pointsPerReception) {

    /**
     * Team counts an ad-hoc league may be spun up at.
     *
     * Capped at 14 rather than the 8–16 of the original design, and the reason
     * is board depth, not taste: 14 × 20 = 280 picks stays inside the player
     * pool the engine assumes is meaningfully ordered, and above 14 it stops
     * doing so — {@code search_rank} degrades badly in the deep end and FFC's
     * feed does not reach that far. See claude/next-features-roadmap.md §3.1.
     * One domain across the whole app means the UI only ever renders one
     * dropdown.
     */
    public static final Set<Integer> SUPPORTED_TEAM_COUNTS = Set.of(8, 10, 12, 14);

    /** The standard roster, in Sleeper's own slot vocabulary and order. */
    public static final List<String> STANDARD_ROSTER = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");

    public static final int STANDARD_ROUNDS = 15;
    public static final double STANDARD_PPR = 1.0;

    public LeagueShape {
        if (!SUPPORTED_TEAM_COUNTS.contains(teams)) {
            throw new IllegalArgumentException(
                    "teams must be one of " + SUPPORTED_TEAM_COUNTS.stream().sorted().toList()
                            + ", got " + teams);
        }
        if (rounds < 1) throw new IllegalArgumentException("rounds must be positive, got " + rounds);
        if (rosterPositions == null || rosterPositions.isEmpty()) {
            throw new IllegalArgumentException("rosterPositions must not be empty");
        }
        rosterPositions = List.copyOf(rosterPositions);
    }

    /** The default shape at a given size: standard roster, 15 rounds, full PPR. */
    public static LeagueShape standard(int teams) {
        return new LeagueShape(teams, STANDARD_ROUNDS, STANDARD_ROSTER, STANDARD_PPR);
    }

    public LeagueSettings toSettings() {
        return new LeagueSettings(teams, rounds, rosterPositions, pointsPerReception);
    }

    public int totalPicks() {
        return teams * rounds;
    }
}
