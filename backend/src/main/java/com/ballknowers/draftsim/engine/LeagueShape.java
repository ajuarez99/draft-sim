package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;

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
 * Fixed per sport, not caller-supplied (§3.3). Football's template is
 * fantasy(heart)'s — QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 bench — which is also
 * every other football league in this project's "League facts"; basketball's is
 * the real Ball Knowers NBA league's own (see {@link #NBA_ROSTER}). Stacking an
 * untested dimension (arbitrary roster shapes) on top buys nothing: no league
 * anyone here drafts in has a different one. A roster editor is deferred, not
 * designed-out — the field is already a {@code List<String>} because Sleeper's
 * own is.
 */
public record LeagueShape(Sport sport, int teams, int rounds, List<String> rosterPositions,
                          double pointsPerReception, int reversalRound) {

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

    /** The standard football roster, in Sleeper's own slot vocabulary and order. */
    public static final List<String> STANDARD_ROSTER = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");

    /**
     * The standard basketball roster. Not invented: this is the real Ball
     * Knowers NBA league's own {@code league.roster_positions}, identical
     * across all three of its seasons (2024/2025/2026), read out of the
     * database rather than transcribed from a screenshot. It is also exactly
     * the nine starting slots {@code sport/BasketballRules} hardcodes — that
     * class's whole lineup model was proven against this shape and no other —
     * plus the five bench slots it reads generically off
     * {@link com.ballknowers.draftsim.domain.LeagueSettings#benchSlots()}.
     */
    public static final List<String> NBA_ROSTER = List.of(
            "PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL",
            "BN", "BN", "BN", "BN", "BN");

    public static final int STANDARD_ROUNDS = 15;
    public static final int NBA_ROUNDS = 14;
    public static final double STANDARD_PPR = 1.0;

    /** This sport's default roster template. */
    public static List<String> standardRoster(Sport sport) {
        return sport == Sport.NBA ? NBA_ROSTER : STANDARD_ROSTER;
    }

    /**
     * This sport's default round count — one per roster slot, which is what
     * both leagues actually run (football 15, basketball 14).
     */
    public static int standardRounds(Sport sport) {
        return sport == Sport.NBA ? NBA_ROUNDS : STANDARD_ROUNDS;
    }

    public LeagueShape {
        if (!SUPPORTED_TEAM_COUNTS.contains(teams)) {
            throw new IllegalArgumentException(
                    "teams must be one of " + SUPPORTED_TEAM_COUNTS.stream().sorted().toList()
                            + ", got " + teams);
        }
        if (sport == null) throw new IllegalArgumentException("sport is required");
        if (rounds < 1) throw new IllegalArgumentException("rounds must be positive, got " + rounds);
        if (rosterPositions == null || rosterPositions.isEmpty()) {
            throw new IllegalArgumentException("rosterPositions must not be empty");
        }
        // Mirrors LeagueController's own bound on the reversal-round override:
        // a reversal at round 1 is not a reversal, and one past the last round
        // never fires. Either is a caller bug worth failing on rather than
        // silently drafting plain snake.
        if (reversalRound < 0 || reversalRound == 1 || reversalRound > rounds) {
            throw new IllegalArgumentException(
                    "reversalRound must be 0 (plain snake) or between 2 and " + rounds + ", got " + reversalRound);
        }
        rosterPositions = List.copyOf(rosterPositions);
    }

    /**
     * The default football shape at a given size: standard roster, 15 rounds,
     * full PPR, plain snake. Kept as the no-sport overload because every
     * existing caller of it is football and means it.
     */
    public static LeagueShape standard(int teams) {
        return standard(Sport.NFL, teams);
    }

    /**
     * This sport's default shape at a given size. Plain snake: a bare shape has
     * no {@code draft} row behind it to read a {@code reversal_round} off of, so
     * a caller who has one (the mock room cloning a real league) passes it to
     * the canonical constructor instead.
     */
    public static LeagueShape standard(Sport sport, int teams) {
        return new LeagueShape(sport, teams, standardRounds(sport), standardRoster(sport), STANDARD_PPR, 0);
    }

    public LeagueSettings toSettings() {
        return new LeagueSettings(sport, teams, rounds, rosterPositions, pointsPerReception, reversalRound);
    }

    public int totalPicks() {
        return teams * rounds;
    }
}
