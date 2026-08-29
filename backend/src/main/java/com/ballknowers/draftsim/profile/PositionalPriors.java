package com.ballknowers.draftsim.profile;

import com.ballknowers.draftsim.domain.Position;

import java.util.EnumMap;
import java.util.Map;

/**
 * P(position | round), fit from every completed draft that has been ingested
 * and smoothed with Dirichlet pseudo-counts.
 *
 * The design doc wants this fit on a large corpus of public drafts. That corpus
 * is not wired up, so this is fit on a handful of drafts instead and smoothed
 * heavily to compensate. With alpha = 8 and ~24 observations per (round,
 * position) cell, the prior is doing more work than the data. That is the
 * intended behavior at this sample size, not a bug, but it does mean this term
 * mostly encodes "kickers go late, QBs are spread out" rather than anything
 * specific to these managers.
 */
public final class PositionalPriors {

    private final Map<Integer, Map<Position, Double>> byRound;
    private final Map<Position, Double> overall;
    private final int observations;

    PositionalPriors(Map<Integer, Map<Position, Double>> byRound,
                     Map<Position, Double> overall,
                     int observations) {
        this.byRound = byRound;
        this.overall = overall;
        this.observations = observations;
    }

    public double probability(int round, Position pos) {
        Map<Position, Double> table = byRound.get(round);
        if (table == null) return overall.getOrDefault(pos, 1.0 / Position.values().length);
        return table.getOrDefault(pos, 1e-6);
    }

    /**
     * Log probability, which is what the scoring function adds. Kept separate so
     * the weight on this term stays interpretable: it is a log-odds nudge, not a
     * probability multiplier.
     */
    public double logProbability(int round, Position pos) {
        return Math.log(Math.max(probability(round, pos), 1e-9));
    }

    public int observations() {
        return observations;
    }

    public Map<Position, Double> forRound(int round) {
        Map<Position, Double> t = byRound.get(round);
        return t == null ? new EnumMap<>(overall) : new EnumMap<>(t);
    }

    public static PositionalPriors uniform() {
        Map<Position, Double> flat = new EnumMap<>(Position.class);
        for (Position p : Position.values()) flat.put(p, 1.0 / Position.values().length);
        return new PositionalPriors(Map.of(), flat, 0);
    }
}
