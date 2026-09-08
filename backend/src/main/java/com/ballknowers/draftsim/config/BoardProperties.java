package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * How the derived board is built. There is no true 14-team PPR ADP source
 * wired up, so the board blends Sleeper's own popularity rank with the actual
 * pick order of completed drafts. Both are approximations and the UI says so.
 */
@ConfigurationProperties(prefix = "draftsim.board")
public record BoardProperties(
        /** Weight on observed draft order vs. Sleeper search_rank, in [0,1]. */
        double observedWeight,
        /**
         * Sleeper draft ids whose pick order feeds the observed half, keyed by
         * sport code ("nfl"/"nba") -- Phase 4 of
         * claude/multi-sport-and-rebrand.md. Was a flat untagged list; each
         * sport draws only from its own drafts now.
         */
        Map<String, List<String>> observedDrafts,
        /** Team count the blended board is expressed in. */
        int referenceTeams,
        /**
         * A pick can be scored for reach only against a board captured near it
         * in time. Beyond this many days, adp_at_time is left null and the pick
         * is excluded from profile fitting rather than measured against a board
         * from a different season.
         */
        int maxBoardLagDays
) {
    /** This sport's configured observed drafts, or none if the key is absent. */
    public List<String> observedDrafts(Sport sport) {
        return observedDrafts == null ? List.of() : observedDrafts.getOrDefault(sport.code(), List.of());
    }
}
