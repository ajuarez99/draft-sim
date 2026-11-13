package com.ballknowers.draftsim.historical;

import java.util.List;

/**
 * How one replayed season is cut into a regular season and playoffs (spec 063). Pure: no Spring, no I/O.
 *
 * <p>{@link #PLAYOFF_TEAMS} and {@link #PLAYOFF_WEEKS} are the bracket's shape, not tunables. The configured
 * regular-season length is a league-format choice (Allan 2026-11-13), not a measurement.
 *
 * <ul>
 *   <li>The season has room for the configured regular season plus three playoff weeks: regular = configured,
 *       playoffs = the next three weeks, anything after is unused.
 *   <li>Otherwise, if what is left after three playoff weeks still holds one full round robin and there are at
 *       least six teams: regular = {@code seasonWeeks - 3}, {@link #shortened}, nothing unused.
 *   <li>Otherwise there are no playoffs: regular = the whole season, no playoff weeks.
 * </ul>
 */
public record SeasonFormat(int version, int seasonWeeks, int configuredRegularSeasonWeeks, int regularSeasonWeeks,
                           List<Integer> playoffWeeks, int unusedWeeks, boolean shortened) {

    public static final int PLAYOFF_TEAMS = 6;
    public static final int PLAYOFF_WEEKS = 3;
    /** The stored format version this class produces; an old stored result has no format at all. */
    public static final int VERSION = 2;

    public SeasonFormat {
        playoffWeeks = List.copyOf(playoffWeeks);
    }

    public static SeasonFormat of(int seasonWeeks, int configuredRegularWeeks, int teams) {
        if (teams >= PLAYOFF_TEAMS && seasonWeeks >= configuredRegularWeeks + PLAYOFF_WEEKS) {
            return new SeasonFormat(VERSION, seasonWeeks, configuredRegularWeeks, configuredRegularWeeks,
                    playoffWeeksAfter(configuredRegularWeeks), seasonWeeks - configuredRegularWeeks - PLAYOFF_WEEKS,
                    false);
        }
        if (teams >= PLAYOFF_TEAMS && seasonWeeks - PLAYOFF_WEEKS >= teams - 1) {
            int regular = seasonWeeks - PLAYOFF_WEEKS;
            return new SeasonFormat(VERSION, seasonWeeks, configuredRegularWeeks, regular,
                    playoffWeeksAfter(regular), 0, true);
        }
        return new SeasonFormat(VERSION, seasonWeeks, configuredRegularWeeks, seasonWeeks, List.of(), 0, false);
    }

    private static List<Integer> playoffWeeksAfter(int regular) {
        return List.of(regular + 1, regular + 2, regular + 3);
    }

    public boolean hasPlayoffs() {
        return !playoffWeeks.isEmpty();
    }

    /** The last week the replay scores for standings or the bracket. */
    public int lastPlayedWeek() {
        return hasPlayoffs() ? playoffWeeks.get(playoffWeeks.size() - 1) : seasonWeeks;
    }

    /** The stored form. The one place a {@link HistoricalResult.Format} is built. */
    public HistoricalResult.Format toResult() {
        return new HistoricalResult.Format(version, seasonWeeks, configuredRegularSeasonWeeks, regularSeasonWeeks,
                playoffWeeks, unusedWeeks, shortened);
    }
}
