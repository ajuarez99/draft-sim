package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Bound from config/weights.yml, which lives outside the jar on purpose.
 * None of these values are fit to data. They are chosen to be directionally
 * sane and are meant to be replaced by fitted values once enough seasons exist.
 */
@ConfigurationProperties(prefix = "draftsim.scoring")
public record ScoringProperties(SportScoring football) {

    // Named SportScoring, not Sport -- it collides conceptually with
    // domain.Sport, and forSport(Sport) below would be unreadable if this
    // record shared its name.
    public record SportScoring(
            Weights weights,
            double adpScale,
            double valueDeltaClamp,
            double valueDecay,
            double benchFloor,
            int runWindow,
            double runRecencyDecay,
            Map<String, Integer> latestRounds,
            double temperature,
            int candidatePool
    ) {}

    public record Weights(
            double adp,
            double positionalPrior,
            double rosterNeed,
            double runPressure
    ) {}

    /**
     * Resolves the scoring block for a sport. Basketball has none configured
     * yet -- weights.yml gains {@code scoring.basketball} in Phase 4 -- so
     * this throws rather than silently handing a basketball request
     * football's valueDecay/adpScale/benchFloor/runWindow/latestRounds.
     */
    public SportScoring forSport(Sport sport) {
        return switch (sport) {
            case NFL -> football();
            case NBA -> throw new IllegalStateException(
                    "basketball scoring is not configured yet; weights.yml gains scoring.basketball in Phase 4");
        };
    }
}
