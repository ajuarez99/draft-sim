package com.ballknowers.draftsim.domain;

import java.util.List;
import java.util.Optional;

public enum Position {
    QB, RB, WR, TE, K, DEF;

    /** Positions a standard FLEX slot accepts. */
    public static final List<Position> FLEX = List.of(RB, WR, TE);

    /**
     * Sleeper uses "DEF" in fantasy_positions and "DST"/"D/ST" nowhere, but
     * other sources vary. Unknown values (LB, DB, OL, ...) are not fantasy
     * relevant in this format and are dropped rather than guessed at.
     */
    public static Optional<Position> fromSleeper(String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.trim().toUpperCase()) {
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
