package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FFC as a third board input alongside search_rank and observed order — see
 * claude/adp-sources.md. Scoped down from that doc's full design: one fetch
 * cell (the target league's team count and scoring), not the 8-14-team matrix,
 * because verifying the live API this session found that FFC's `teams`
 * parameter, though validated, currently returns identical data for every team
 * count on the same scoring format — see {@code derived}/{@code derivation} on
 * the stored rows and claude/adp-sources.md's "Verified this session" note.
 * Building the full per-size matrix today would model a distinction that does
 * not yet exist in the data.
 */
@ConfigurationProperties(prefix = "draftsim.adp")
public record AdpProperties(Ffc ffc) {

    public record Ffc(
            boolean enabled,
            int teams,
            /** "standard" | "half-ppr" | "ppr" — FFC's path segment. */
            String format,
            int year,
            /** Weight in the three-way blend with search_rank and observed order. */
            double weight,
            /** Below this many contributing mock drafts, the cell is dropped rather than trusted. */
            int minDrafts
    ) {}
}
