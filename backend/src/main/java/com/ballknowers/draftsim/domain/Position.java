package com.ballknowers.draftsim.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every constant is tagged with the sport it belongs to, so a caller that
 * must not mix sports (a Dirichlet denominator, a per-round iteration, a
 * table handed out for display) can scope itself with {@link #forSport} instead
 * of iterating {@link #values()} and silently pulling in the other sport's
 * positions. See claude/multi-sport-and-rebrand.md Phase 3a for the full
 * reasoning and the list of call sites this distinction actually matters at.
 *
 * {@code G}/{@code F}/{@code UTIL}/{@code FLEX}/{@code BN} are roster SLOTS,
 * not positions, and deliberately stay out of this enum -- a player has a
 * position; a roster has slots that some positions are eligible for.
 */
public enum Position {
    QB(Sport.NFL), RB(Sport.NFL), WR(Sport.NFL), TE(Sport.NFL), K(Sport.NFL), DEF(Sport.NFL),
    PG(Sport.NBA), SG(Sport.NBA), SF(Sport.NBA), PF(Sport.NBA), C(Sport.NBA);

    private final Sport sport;

    Position(Sport sport) {
        this.sport = sport;
    }

    public Sport sport() {
        return sport;
    }

    /**
     * This sport's positions, in declaration order. The sport-scoped
     * replacement for {@link #values()} at every site that is a denominator,
     * an iteration whose result is displayed or fit, or otherwise must not
     * see the other sport's positions. Deliberately NOT used to size arrays
     * indexed by {@link #ordinal()} -- see e.g. {@code FootballRules.POSITIONS}
     * and {@code PickDecider}'s scratch arrays, which stay sized to
     * {@code values().length} on purpose.
     */
    public static List<Position> forSport(Sport sport) {
        return Arrays.stream(values()).filter(p -> p.sport == sport).toList();
    }

    /** Positions a standard FLEX slot accepts. */
    public static final List<Position> FLEX = List.of(RB, WR, TE);

    /**
     * Sleeper uses "DEF" in fantasy_positions and "DST"/"D/ST" nowhere, but
     * other sources vary. Unknown values (LB, DB, OL, ...) are not fantasy
     * relevant in this format and are dropped rather than guessed at.
     *
     * Sport-aware, because the same raw string means different things per
     * sport. Notably: Sleeper's nba player dump carries ~30 entries whose
     * {@code fantasy_positions} is exactly {@code ["DEF"]} (verified
     * 2026-09-08) -- nothing to do with a defense/special-teams unit, just
     * Sleeper's archive tagging some non-fantasy-relevant nba record with a
     * value that happens to collide with football's DEF. Those must be
     * dropped for basketball, not mapped onto football's {@link #DEF}, which
     * is why this cannot be sport-agnostic.
     */
    public static Optional<Position> fromSleeper(String raw, Sport sport) {
        if (raw == null) return Optional.empty();
        String v = raw.trim().toUpperCase();
        if (sport == Sport.NBA) {
            return switch (v) {
                case "PG" -> Optional.of(PG);
                case "SG" -> Optional.of(SG);
                case "SF" -> Optional.of(SF);
                case "PF" -> Optional.of(PF);
                case "C" -> Optional.of(C);
                // Includes the ~30 nba "DEF" entries above -- not fantasy
                // relevant in this format, dropped rather than mapped onto
                // football's DEF.
                default -> Optional.empty();
            };
        }
        return switch (v) {
            case "QB" -> Optional.of(QB);
            case "RB", "FB" -> Optional.of(RB);
            case "WR" -> Optional.of(WR);
            case "TE" -> Optional.of(TE);
            case "K", "PK" -> Optional.of(K);
            case "DEF", "DST", "D/ST" -> Optional.of(DEF);
            default -> Optional.empty();
        };
    }

    public boolean isFlexEligible() {
        return FLEX.contains(this);
    }
}
