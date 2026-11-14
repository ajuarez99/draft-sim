package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.historical.SeasonSkew;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code historical-season-pick} in application.yml: how the Time Machine's random-year draw leans toward
 * recent seasons. Its own record, like {@link HistoricalBotProperties}, so neither sport's existing properties
 * constructor (and the tests that call them) changes.
 *
 * <p>Basketball only. Football offers 2000 onward, so there is no old era to lean away from. A missing block is a
 * flat draw.
 */
@ConfigurationProperties(prefix = "historical-season-pick")
public record HistoricalSeasonPickProperties(SeasonSkew nba) {

    public HistoricalSeasonPickProperties {
        nba = nba == null ? SeasonSkew.NONE : nba;
    }
}
