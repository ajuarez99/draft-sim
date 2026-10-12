package com.ballknowers.draftsim.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Preseason roster-strength settings (spec 030), bound from {@code draftsim.preseason} in
 * config/weights.yml. Both are hand-set and ARBITRARY, not fitted to anything.
 *
 * <p>{@code favoriteMinGapPct}: a roster whose projected total is within this percentage of the
 * top roster's (gap over the lower total) is a co-leader, so a margin the projections cannot
 * resolve is reported as a tie rather than as a favorite.
 *
 * <p>{@code staleAfterDays}: how many days since the projection source last changed before the
 * page says its numbers are N days old.
 *
 * <p>Primitives with {@code @Positive}, on purpose (plan review S10): a missing or misspelled key
 * would otherwise bind silently to 0, and a 0 gap means only exact ties are ever called ties. The
 * health check's {@code weightsLoaded} only looks at the scoring block, so it could not see that.
 * A missing or non-positive value fails startup instead.
 */
@Validated
@ConfigurationProperties(prefix = "draftsim.preseason")
public record PreseasonProperties(@Positive double favoriteMinGapPct, @Positive int staleAfterDays) {}
