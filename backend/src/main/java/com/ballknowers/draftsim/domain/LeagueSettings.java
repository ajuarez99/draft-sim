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
        Sport sport,
        int teams,
        int rounds,
        List<String> rosterPositions,   // Sleeper's raw slot list, in order
        double pointsPerReception,
        /**
         * Sleeper's {@code settings.reversal_round} for the draft this
         * simulation is running (0 = plain snake for the whole draft). Carried
         * here rather than as a separate parameter threaded through
         * {@code DraftContext}/{@code DraftSimulator}/{@code MonteCarloRunner}
         * signatures: this record already exists specifically to carry
         * per-simulated-draft facts like {@link #sport} down to those hot-path
         * callers (see {@link com.ballknowers.draftsim.store.LeagueRepository
         * #toSettings}, which builds one per draft, not per league), so it is
         * one field on an existing carrier instead of a parameter on five
         * method signatures. multi-sport-and-rebrand.md Phase 5/6.
         */
        int reversalRound
) {
    /**
     * Back-compat: existing callers that never had a reversal round to report
     * (an ad-hoc {@code LeagueShape}, or a test with no basketball-reversal
     * concern) get plain snake, same convention as
     * {@link com.ballknowers.draftsim.store.DraftRepository#upsert}'s own
     * 8-arg overload.
     */
    public LeagueSettings(Sport sport, int teams, int rounds, List<String> rosterPositions,
                          double pointsPerReception) {
        this(sport, teams, rounds, rosterPositions, pointsPerReception, 0);
    }

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
