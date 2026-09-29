package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.LeagueRepository;

import java.time.Duration;
import java.time.Instant;

/**
 * The pure rules for whether a league-season should be refreshed on a visit
 * (specs/009-auto-data-refresh, research R3 and the refresh contract). No
 * clock, no I/O: the caller passes {@code now}.
 */
public final class RefreshDecision {

    private RefreshDecision() {}

    public enum Decision { START, SKIP_FRESH, SKIP_COMPLETE, SKIP_RECENT_FAILURE }

    /**
     * @param state        the season's {@code league_refresh} row, or null if it
     *                     has never been refreshed
     * @param leagueStatus the stored {@code league.status}; null means not yet
     *                     known and is never complete (V21)
     */
    public static Decision decide(LeagueRefreshRepository.Row state, String leagueStatus, Instant now) {
        if (state == null) return Decision.START;
        if (state.loadedComplete()) return Decision.SKIP_COMPLETE;

        if (failed(state)) {
            Duration sinceFailure = Duration.between(state.lastFailureAt(), now);
            if (sinceFailure.compareTo(RefreshProperties.RETRY_AFTER_FAILURE) < 0) {
                return Decision.SKIP_RECENT_FAILURE;
            }
            return Decision.START;
        }

        // A season that reads complete but was never loaded after completing
        // (research R3: production NBA 2025) gets its first load now, not when
        // the hour is up -- but only when it has never succeeded. Once it has,
        // the normal stale/retry rules apply: loaded_complete also waits for every
        // per-game week to be final, so a complete-not-loaded season may stay that
        // way for a while and must not START on every visit (review, 2026-09-28).
        if (state.lastSuccessAt() == null && LeagueRepository.LeagueRow.isComplete(leagueStatus)) {
            return Decision.START;
        }

        if (state.lastSuccessAt() != null
                && Duration.between(state.lastSuccessAt(), now).compareTo(RefreshProperties.STALE_AFTER) < 0) {
            return Decision.SKIP_FRESH;
        }
        return Decision.START;
    }

    /** True when the last attempt failed and no newer success exists. */
    public static boolean failed(LeagueRefreshRepository.Row state) {
        if (state == null || state.lastFailureAt() == null) return false;
        return state.lastSuccessAt() == null || state.lastFailureAt().isAfter(state.lastSuccessAt());
    }

    /**
     * Whether a finished run may mark its season {@code loaded_complete}: only a
     * run that succeeded while the league read {@code complete} <b>and</b> after
     * every per-game week of that sport-season was final. A failed run, or one
     * that saw the league still in season, never does.
     *
     * <p>The per-game condition was added in review (2026-09-28): a league can
     * read {@code complete} within {@code WEEK_FINAL_AFTER} of its last game,
     * before Sleeper's stat corrections settle. Marking the season complete then
     * would skip it forever, freezing a non-final per-game week.
     *
     * @param statusSeenInThisWalk   the league status the walk itself stored
     * @param perGameWeeksAllFinal   every {@code sport_week_stats} week of the
     *                               sport-season is final, and there is at least one
     */
    public static boolean completeAfter(String statusSeenInThisWalk, boolean succeeded,
                                        boolean perGameWeeksAllFinal) {
        return succeeded && perGameWeeksAllFinal && LeagueRepository.LeagueRow.isComplete(statusSeenInThisWalk);
    }
}
