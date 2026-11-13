package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec 056 (Football Time Machine), bound from {@code historical-nfl} in application.yml. A separate record from
 * {@link HistoricalProperties} on purpose (review F10): 053's constructor and its integration-test callers stay
 * untouched, and the two sports cannot read each other's values by accident.
 *
 * <p>Provenance of the numbers, kept as plainly here as in the yml. Nothing below was fitted to any data.
 * <ul>
 *   <li>{@code teams}, {@code rounds}, {@code roster}, {@code reversalRound} -- hand-set (spec 056 FR-002): a
 *       12-team, 15-round league with QB, 2 RB, 2 WR, TE, FLEX and 8 bench; reversal 0 is a plain snake.
 *   <li>{@code scoring} -- hand-set (FR-003 as amended after review): full PPR with INT -1 and fumble lost -2 (the
 *       real league's Sleeper values, review F13).
 *   <li>{@code rankStandardGames}, {@code rankMinGames}, {@code rankLookbackSeasons},
 *       {@code rookiePositionalOffset}, {@code rookieMaxRound} -- hand-set (F6, F14), arbitrary, labelled so.
 *   <li>{@code expectedGames} -- a schedule fact, not a tuning knob: the regular-season games the NFL played that
 *       year (the import compares what it loaded against it, FR-014).
 *   <li>{@code regularSeasonWeeks} -- a league-format choice (spec 063, Allan 2026-11-13), not a measurement:
 *       the weeks of round robin before the three playoff weeks.
 *   <li>{@code firstOffered}, {@code lastOffered} -- the football seasons a user can be dealt.
 * </ul>
 */
@ConfigurationProperties(prefix = "historical-nfl")
public record NflHistoricalProperties(String statsUrlTemplate, String draftPicksUrl, Integer teams, Integer rounds,
                                      List<String> roster, Integer reversalRound, Map<String, Double> scoring,
                                      Integer rankStandardGames, Integer rankMinGames, Integer rankLookbackSeasons,
                                      Integer rookiePositionalOffset, Integer rookieMaxRound,
                                      Map<Integer, Integer> expectedGames, Integer firstOffered,
                                      Integer lastOffered, Integer regularSeasonWeeks) {

    public NflHistoricalProperties {
        if (statsUrlTemplate == null || !statsUrlTemplate.contains("{season}")) {
            throw new IllegalArgumentException("historical-nfl.stats-url-template must contain {season}, got "
                    + statsUrlTemplate);
        }
        if (draftPicksUrl == null || draftPicksUrl.isBlank()) {
            throw new IllegalArgumentException("historical-nfl.draft-picks-url must be set");
        }
        positive("teams", teams);
        positive("rounds", rounds);
        if (roster == null || roster.isEmpty()) {
            throw new IllegalArgumentException("historical-nfl.roster must be a non-empty list");
        }
        roster = List.copyOf(roster);
        if (reversalRound == null || reversalRound < 0) {
            throw new IllegalArgumentException("historical-nfl.reversal-round must be >= 0, got " + reversalRound);
        }
        if (scoring == null || scoring.isEmpty()) {
            throw new IllegalArgumentException("historical-nfl.scoring must be a non-empty map");
        }
        scoring = Collections.unmodifiableMap(new LinkedHashMap<>(scoring));   // keep config order
        positive("rank-standard-games", rankStandardGames);
        positive("rank-min-games", rankMinGames);
        positive("rank-lookback-seasons", rankLookbackSeasons);
        positive("rookie-positional-offset", rookiePositionalOffset);
        positive("rookie-max-round", rookieMaxRound);
        positive("first-offered", firstOffered);
        positive("last-offered", lastOffered);
        positive("regular-season-weeks", regularSeasonWeeks);
        if (firstOffered > lastOffered) {
            throw new IllegalArgumentException("historical-nfl.first-offered " + firstOffered
                    + " is after last-offered " + lastOffered);
        }
        if (expectedGames == null || expectedGames.isEmpty()) {
            throw new IllegalArgumentException("historical-nfl.expected-games must be a non-empty map");
        }
        for (int y = firstOffered; y <= lastOffered; y++) {
            Integer g = expectedGames.get(y);
            if (g == null || g <= 0) {
                throw new IllegalArgumentException("historical-nfl.expected-games has no positive entry for " + y
                        + " (every offered season needs one)");
            }
        }
        expectedGames = Collections.unmodifiableMap(new java.util.TreeMap<>(expectedGames));
    }

    private static void positive(String name, Integer v) {
        if (v == null || v <= 0) {
            throw new IllegalArgumentException("historical-nfl." + name + " must be a positive number, got " + v);
        }
    }
}
