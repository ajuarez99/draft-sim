package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.historical.BotTuning;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spec 062, bound from {@code historical-bots} in application.yml: how the Time Machine's computer teams differ
 * from the shared draft engine, per sport. Its own record so neither sport's existing properties constructor (and
 * the tests that call them) changes.
 *
 * <p>Every number is hand-set and arbitrary. A sport with no block, or a block with a value left out, keeps the
 * shared engine's value from {@code config/weights.yml}.
 */
@ConfigurationProperties(prefix = "historical-bots")
public record HistoricalBotProperties(BotTuning nba, BotTuning nfl) {

    public HistoricalBotProperties {
        nba = nba == null ? BotTuning.NONE : nba;
        nfl = nfl == null ? BotTuning.NONE : nfl;
    }
}
