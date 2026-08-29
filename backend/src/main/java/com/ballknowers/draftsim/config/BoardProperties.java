package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * How the derived board is built. There is no true 14-team PPR ADP source
 * wired up, so the board blends Sleeper's own popularity rank with the actual
 * pick order of completed drafts. Both are approximations and the UI says so.
 */
@ConfigurationProperties(prefix = "draftsim.board")
public record BoardProperties(
        /** Weight on observed draft order vs. Sleeper search_rank, in [0,1]. */
        double observedWeight,
        /** Sleeper draft ids whose pick order feeds the observed half. */
        List<String> observedDrafts,
        /** Team count the blended board is expressed in. */
        int referenceTeams,
        /**
         * A pick can be scored for reach only against a board captured near it
         * in time. Beyond this many days, adp_at_time is left null and the pick
         * is excluded from profile fitting rather than measured against a board
         * from a different season.
         */
        int maxBoardLagDays
) {}
