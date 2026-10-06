package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Draft-grade settings (spec 018), bound from {@code draftsim.draft-grades} in
 * config/weights.yml. ARBITRARY: not fitted to anything.
 *
 * <p>{@code minPicksPerPosition} is how many graded picks a position needs before a line of production
 * against ln(pick number) is fitted for it; a position with fewer gets no baseline and no value.
 *
 * <p>A <b>missing</b> block binds to a null field and startup still succeeds (weights.yml is an
 * optional import); there is deliberately no silent default. A value that is <b>present</b> is
 * validated 3 to 30 and a bad one fails startup.
 */
@ConfigurationProperties(prefix = "draftsim.draft-grades")
public record DraftGradeProperties(Integer minPicksPerPosition) {

    public DraftGradeProperties {
        if (minPicksPerPosition != null && (minPicksPerPosition < 3 || minPicksPerPosition > 30)) {
            throw new IllegalArgumentException(
                    "draftsim.draft-grades.min-picks-per-position must satisfy 3 <= m <= 30, got " + minPicksPerPosition);
        }
    }

    public boolean loaded() {
        return minPicksPerPosition != null;
    }
}
