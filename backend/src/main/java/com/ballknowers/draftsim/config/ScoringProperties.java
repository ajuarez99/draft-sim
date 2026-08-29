package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Bound from config/weights.yml, which lives outside the jar on purpose.
 * None of these values are fit to data. They are chosen to be directionally
 * sane and are meant to be replaced by fitted values once enough seasons exist.
 */
@ConfigurationProperties(prefix = "draftsim.scoring")
public record ScoringProperties(Sport football) {

    public record Sport(
            Weights weights,
            double adpScale,
            double valueDeltaClamp,
            double valueDecay,
            double benchFloor,
            int runWindow,
            double runRecencyDecay,
            Map<String, Integer> earliestRound,
            double temperature,
            int candidatePool
    ) {}

    public record Weights(
            double adp,
            double positionalPrior,
            double rosterNeed,
            double runPressure
    ) {}
}
