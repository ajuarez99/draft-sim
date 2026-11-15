package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code historical-rookies} in application.yml: where a first-season basketball player goes on a Time
 * Machine board. Its own record, like {@link HistoricalBotProperties}, so the existing properties constructors (and
 * the tests that call them) do not change.
 *
 * <p>Every number is hand-set and arbitrary, a guess at where rookies go in real fantasy drafts. Nothing was
 * fitted, and there is no historical ADP to fit it to. A missing block leaves rookies where they were: in a
 * random order behind every veteran.
 *
 * <p>Basketball only. Football places its early draftees by position (spec 056 F14,
 * {@code historical-nfl.rookie-positional-offset}).
 *
 * @param nba null when the block is absent
 */
@ConfigurationProperties(prefix = "historical-rookies")
public record HistoricalRookieProperties(Nba nba) {

    /**
     * The player drafted at overall pick p is ranked level with the veteran at board rank
     * {@code firstRank + rankStep * (p - 1)}.
     *
     * @param firstRank            the veteran rank the first overall pick is level with
     * @param rankStep             how many veteran ranks each later draft pick costs
     * @param maxSeasonsSinceDraft how old a draft may be and still place a player who has never played (a
     *                             draftee who sat out or stayed abroad); 0 means this year's draft only
     */
    public record Nba(Integer firstRank, Integer rankStep, Integer maxSeasonsSinceDraft) {
        public Nba {
            if (firstRank == null || firstRank < 1) {
                throw new IllegalArgumentException("historical-rookies.nba.first-rank must be >= 1, got " + firstRank);
            }
            if (rankStep == null || rankStep < 1) {
                throw new IllegalArgumentException("historical-rookies.nba.rank-step must be >= 1, got " + rankStep);
            }
            if (maxSeasonsSinceDraft == null || maxSeasonsSinceDraft < 0) {
                throw new IllegalArgumentException("historical-rookies.nba.max-seasons-since-draft must be >= 0, got "
                        + maxSeasonsSinceDraft);
            }
        }
    }
}
