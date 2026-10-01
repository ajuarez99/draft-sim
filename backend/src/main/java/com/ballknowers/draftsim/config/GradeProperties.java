package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Letter-grade cutoffs (spec 013 T071), bound from {@code draftsim.grades} in
 * config/weights.yml. ARBITRARY: not fitted to anything.
 *
 * <p>A <b>missing</b> block binds to an empty list and startup still succeeds (weights.yml is an
 * optional import); every grade is then null. A block that is <b>present</b> is validated and a bad
 * one fails startup: percentiles strictly increasing, the last exactly 100.
 */
@ConfigurationProperties(prefix = "draftsim.grades")
public record GradeProperties(List<Cutoff> cutoffs) {

    /** A team takes the first cutoff whose {@code maxPercentile} is >= its percentile (0 = best). */
    public record Cutoff(double maxPercentile, String grade) {}

    public GradeProperties {
        cutoffs = cutoffs == null ? List.of() : List.copyOf(cutoffs);
        if (!cutoffs.isEmpty()) {
            double prev = Double.NEGATIVE_INFINITY;
            for (Cutoff c : cutoffs) {
                if (c == null || c.grade() == null || c.grade().isBlank()) {
                    throw new IllegalArgumentException("draftsim.grades.cutoffs: every entry needs a grade");
                }
                if (!(c.maxPercentile() > prev)) {
                    throw new IllegalArgumentException(
                            "draftsim.grades.cutoffs: maxPercentile must strictly increase, got "
                                    + c.maxPercentile() + " after " + prev);
                }
                prev = c.maxPercentile();
            }
            if (prev != 100.0) {
                throw new IllegalArgumentException(
                        "draftsim.grades.cutoffs: the last maxPercentile must be 100, got " + prev);
            }
        }
    }

    public boolean loaded() {
        return !cutoffs.isEmpty();
    }
}
