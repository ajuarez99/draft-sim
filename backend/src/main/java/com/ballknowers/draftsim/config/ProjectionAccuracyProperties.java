package com.ballknowers.draftsim.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Spec 036 hand-set numbers (research R10), bound from {@code draftsim.projection-accuracy} and
 * {@code draftsim.projection-snapshot} in config/weights.yml. Every value is ARBITRARY: set before
 * any sample existed and never fitted to a measured accuracy (FR-014).
 *
 * <p>Both blocks are required, like {@link PreseasonProperties}: a missing or misspelled key would
 * otherwise bind to null/0 and quietly turn "too early" into a figure, or the freeze cutoff into
 * midnight. Startup fails instead. Maps are keyed by {@code Sport.code()} ({@code nba} / {@code nfl}).
 *
 * @param projectionAccuracy the "too early" thresholds and the projected-value bands
 * @param projectionSnapshot the per-sport pre-game cutoff and capture lookahead
 */
@Validated
@ConfigurationProperties(prefix = "draftsim")
public record ProjectionAccuracyProperties(@NotNull @Valid Accuracy projectionAccuracy,
                                           @NotNull @Valid Snapshot projectionSnapshot) {

    /**
     * @param minSamples scored samples per sport below which the status is TOO_EARLY
     * @param minCell    samples a breakdown row needs before it shows a figure
     * @param bands      per sport, lower edges (league points) of the 2nd..last band
     */
    public record Accuracy(@NotNull Map<String, Integer> minSamples, @Positive int minCell,
                           @NotNull Map<String, List<Integer>> bands) {}

    /**
     * @param cutoffEt      per sport, "HH:mm" America/New_York on the game's date
     * @param lookaheadDays how many days ahead of today a game is still captured
     */
    public record Snapshot(@NotNull Map<String, String> cutoffEt, @Positive int lookaheadDays) {}

    public int minSamples(String sport) {
        Integer n = projectionAccuracy.minSamples().get(sport);
        if (n == null || n <= 0) throw new IllegalStateException("no positive min-samples for " + sport);
        return n;
    }

    public List<Integer> bands(String sport) {
        List<Integer> b = projectionAccuracy.bands().get(sport);
        if (b == null) throw new IllegalStateException("no bands for " + sport);
        return b;
    }

    /** The sport's cutoff as a wall-clock time in America/New_York. */
    public LocalTime cutoffEt(String sport) {
        String t = projectionSnapshot.cutoffEt().get(sport);
        if (t == null) throw new IllegalStateException("no snapshot cutoff-et for " + sport);
        return LocalTime.parse(t);
    }
}
