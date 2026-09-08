package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * P(position | draft fraction) is fit from ingested drafts. With only a few
 * drafts that table is thin, so it is smoothed toward a flat prior. alpha is a
 * Dirichlet pseudo-count per (bucket, position) cell: higher means trust the
 * data less.
 *
 * buckets is how many equal slices of a draft the table is keyed on, per
 * sport (Phase 4 of claude/multi-sport-and-rebrand.md -- was a flat
 * untagged int). It is deliberately NOT a round count — see
 * {@link com.ballknowers.draftsim.profile.PositionalPriors} — but at 15 it
 * lines up exactly with a 15-round league (football's), and at 14 with a
 * 14-round league (basketball's). A missing or non-positive entry falls back
 * to {@code PositionalPriors.DEFAULT_BUCKETS} at the call site, since
 * weights.yml lives outside the jar and an older copy of it must still boot.
 */
@ConfigurationProperties(prefix = "draftsim.priors")
public record PriorProperties(double alpha, Map<String, Integer> buckets) {
    /** Raw configured bucket count for this sport, or 0 if unset/absent. */
    public int buckets(Sport sport) {
        return buckets == null ? 0 : buckets.getOrDefault(sport.code(), 0);
    }
}
