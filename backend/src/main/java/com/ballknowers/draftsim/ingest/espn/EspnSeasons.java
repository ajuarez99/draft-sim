package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;

/**
 * ESPN seasonId to the app's season number, per sport (spec 035).
 *
 * <p>Measured 2026-10-14: ESPN labels a basketball season by the year it ENDS (the 2026-27 season is
 * {@code seasonId=2027}; the user's real league link says 2027). The app numbers NBA seasons the way
 * Sleeper does, by the year they START: the scratch DB holds NBA leagues 2024, 2025 and 2026, and the
 * 2026 one was drafted on 2026-10-10 for the 2026-27 season. So NBA: app = ESPN - 1. NFL seasons span
 * one calendar year in both systems: same number.
 *
 * <p>Keys, {@code league.season}, {@code previous_league_id} chaining and the UI all use the APP
 * season; only the HTTP calls to ESPN use {@link #toEspn}.
 */
public final class EspnSeasons {

    private EspnSeasons() {}

    public static int toApp(Sport sport, int espnSeasonId) {
        return sport == Sport.NBA ? espnSeasonId - 1 : espnSeasonId;
    }

    public static int toEspn(Sport sport, int appSeason) {
        return sport == Sport.NBA ? appSeason + 1 : appSeason;
    }

    /**
     * The app season a sport is "in" on this date, used when a pasted link has no {@code seasonId}.
     * A heuristic, not a measurement: NFL seasons start in September (Jan-Feb belongs to the season
     * that just ended); NBA seasons start in October and leagues are made from August on, so August
     * onward counts as the new season.
     */
    public static int currentAppSeason(Sport sport, java.time.LocalDate today) {
        int month = today.getMonthValue();
        int cut = sport == Sport.NFL ? 3 : 8;
        return month >= cut ? today.getYear() : today.getYear() - 1;
    }
}
