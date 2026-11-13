package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.ScoringProperties;

/**
 * How the Time Machine's computer teams differ from the shared draft engine (claude/time-machine-bot-strength.md).
 *
 * <p>The engine's weights in {@code config/weights.yml} are guesses made for seats that stand in for real managers
 * drafting off a real ADP board. A Time Machine bot is neither: its board is last season's points, every seat has the
 * neutral profile and the positional priors are uniform. Measured on 2026-11-11 with the shared temperature of 1.0,
 * a user who only pressed auto-finish won 40% of basketball leagues and 36% of football leagues (chance is 8%).
 * This lets the game set its own temperature without touching the shared file, which the real-league simulations
 * and their frozen-output tests read.
 *
 * <p>Hand-set and arbitrary. A null leaves the shared value in place, so {@link #NONE} is exactly the engine as
 * every other caller sees it.
 *
 * @param temperature the softmax temperature for a bot's pick; lower follows the board more closely
 */
public record BotTuning(Double temperature) {

    public static final BotTuning NONE = new BotTuning(null);

    public BotTuning {
        if (temperature != null && (temperature < 0 || temperature.isNaN())) {
            throw new IllegalArgumentException("bot temperature must be >= 0, got " + temperature);
        }
    }

    /** The shared scoring block with this temperature swapped in; every other field is unchanged. */
    public ScoringProperties.SportScoring apply(ScoringProperties.SportScoring base) {
        if (temperature == null) return base;
        return new ScoringProperties.SportScoring(base.weights(), base.adpScale(), base.valueDeltaClamp(),
                base.valueDecay(), base.benchFloor(), base.runWindow(), base.runRecencyDecay(), base.latestRounds(),
                temperature, base.candidatePool());
    }
}
