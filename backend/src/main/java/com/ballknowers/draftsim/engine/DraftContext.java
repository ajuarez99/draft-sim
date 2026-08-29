package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.SportRules;

import java.util.List;
import java.util.Map;

/**
 * Everything a simulation run needs, assembled once and shared read-only across
 * all iterations. Nothing in here is mutated during a run.
 */
public record DraftContext(
        List<BoardEntry> board,                  // sorted by board position
        LeagueSettings settings,
        Map<Integer, ManagerProfile> profileBySlot,
        PositionalPriors priors,
        SportRules rules,
        ScoringProperties.Sport cfg,
        List<Integer> completedPickNumbers,      // picks already made (resume mode)
        Map<Integer, Long> completedPicks        // pickNo -> player id
) {
    public int totalPicks() {
        return settings.teams() * settings.rounds();
    }

    public ManagerProfile profileFor(int slot) {
        return profileBySlot.getOrDefault(slot, ManagerProfile.neutral(-1, "seat " + slot));
    }
}
