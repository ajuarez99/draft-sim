package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P(position | draft fraction) is fit from ingested drafts. With only a few
 * drafts that table is thin, so it is smoothed toward a flat prior. alpha is a
 * Dirichlet pseudo-count per (bucket, position) cell: higher means trust the
 * data less.
 *
 * buckets is how many equal slices of a draft the table is keyed on. It is
 * deliberately NOT a round count — see {@link com.ballknowers.draftsim.profile.PositionalPriors}
 * — but at 15 it lines up exactly with a 15-round league, which is every league
 * ingested so far. 0 or absent falls back to the default, since weights.yml
 * lives outside the jar and an older copy of it must still boot.
 */
@ConfigurationProperties(prefix = "draftsim.priors")
public record PriorProperties(double alpha, int buckets) {}
