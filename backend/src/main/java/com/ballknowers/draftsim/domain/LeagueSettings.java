package com.ballknowers.draftsim.domain;

import java.util.List;
import java.util.Map;

/**
 * Read off the Sleeper league object at ingest, never guessed.
 *
 * fantasy(heart) 2026: 14 teams, 15 rounds, full PPR,
 * QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 bench.
 */
public record LeagueSettings(
        int teams,
        int rounds,
        List<String> rosterPositions,   // Sleeper's raw slot list, in order
        double pointsPerReception
) {
    public int slotCount(String slot) {
        return (int) rosterPositions.stream().filter(slot::equals).count();
    }

    public int flexSlots() {
        // SUPER_FLEX and REC_FLEX exist in other formats; only FLEX is handled here.
        return slotCount("FLEX");
    }

    public int benchSlots() {
        return slotCount("BN");
    }

    /** Dedicated (non-flex) starting slots by position. */
    public Map<Position, Integer> dedicatedStarters() {
        return Map.of(
                Position.QB, slotCount("QB"),
                Position.RB, slotCount("RB"),
                Position.WR, slotCount("WR"),
                Position.TE, slotCount("TE"),
                Position.K, slotCount("K"),
                Position.DEF, slotCount("DEF")
        );
    }

    public int totalStarters() {
        return (int) rosterPositions.stream().filter(s -> !"BN".equals(s) && !"IR".equals(s)).count();
    }
}
