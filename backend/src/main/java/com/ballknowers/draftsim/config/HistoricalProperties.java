package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Spec 053 (historical draft game), bound from {@code historical} in application.yml.
 *
 * <p>Provenance of the numbers, kept as plainly here as in the yml:
 * <ul>
 *   <li>{@code eraThreshold} -- hand-set (FR-007, Allan 2026-11-02), not fitted. A stat counts in a Kaggle
 *       season only if the league tracked it and this share of played box-score rows carry it.
 *   <li>{@code rankStandardGames}, {@code rankMinGames} -- hand-set (F6), arbitrary. Prior-season ranking is
 *       per-game points times the first, among seasons with at least the second.
 *   <li>{@code rankLookbackSeasons} -- hand-set (spec 053 F6 amended at build): the window of prior seasons
 *       (S-1..S-n) whose newest qualifying per-game line ranks a player; 2 missed Jordan 1995-96.
 *   <li>{@code teams}, {@code rounds}, {@code reversalRound} -- the app's standard NBA shape; reversal 0 is
 *       a plain snake (F8).
 *   <li>{@code regularSeasonWeeks} -- a league-format choice (spec 063, Allan 2026-11-13), not a measurement:
 *       the weeks of round robin before the three playoff weeks.
 *   <li>{@code scoring} -- the Legacy Ball Knowers league scoring (FR-002, O1), keyed by Sleeper stat name.
 * </ul>
 */
@ConfigurationProperties(prefix = "historical")
public record HistoricalProperties(String kaggleZipUrl, String kaggleZipSha256, Double eraThreshold,
                                   Integer rankStandardGames, Integer rankMinGames,
                                   Integer rankLookbackSeasons, Integer teams,
                                   Integer rounds, Integer reversalRound, Map<String, Double> scoring,
                                   Integer regularSeasonWeeks) {

    public HistoricalProperties {
        if (kaggleZipUrl == null || kaggleZipUrl.isBlank()) {
            throw new IllegalArgumentException("historical.kaggle-zip-url must be set");
        }
        if (kaggleZipSha256 == null || !kaggleZipSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("historical.kaggle-zip-sha256 must be 64 lowercase hex characters");
        }
        if (eraThreshold == null || eraThreshold <= 0 || eraThreshold > 1) {
            throw new IllegalArgumentException("historical.era-threshold must be in (0, 1], got " + eraThreshold);
        }
        positive("rank-standard-games", rankStandardGames);
        positive("rank-min-games", rankMinGames);
        positive("rank-lookback-seasons", rankLookbackSeasons);
        positive("teams", teams);
        positive("rounds", rounds);
        positive("regular-season-weeks", regularSeasonWeeks);
        if (reversalRound == null || reversalRound < 0) {
            throw new IllegalArgumentException("historical.reversal-round must be >= 0, got " + reversalRound);
        }
        if (scoring == null || scoring.isEmpty()) {
            throw new IllegalArgumentException("historical.scoring must be a non-empty map");
        }
        scoring = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scoring));   // keep config order (finding 11)
    }

    private static void positive(String name, Integer v) {
        if (v == null || v <= 0) {
            throw new IllegalArgumentException("historical." + name + " must be a positive number, got " + v);
        }
    }
}
