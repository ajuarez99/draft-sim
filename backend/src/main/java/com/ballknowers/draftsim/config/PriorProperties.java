package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P(position | round) is fit from ingested drafts. With only a few drafts that
 * table is thin, so it is smoothed toward a flat prior. alpha is a Dirichlet
 * pseudo-count per (round, position) cell: higher means trust the data less.
 */
@ConfigurationProperties(prefix = "draftsim.priors")
public record PriorProperties(double alpha) {}
