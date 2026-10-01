package com.ballknowers.draftsim.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The one source of "now" for code that reasons about it and has to be tested at a pinned
 * instant (specs/014-home-player-spotlight: night completeness, research R6). UTC, because the
 * rule it serves is stated in UTC.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
