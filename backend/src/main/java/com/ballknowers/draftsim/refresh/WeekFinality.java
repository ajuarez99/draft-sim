package com.ballknowers.draftsim.refresh;

/**
 * The one week-finality rule for league weeks (specs/009-auto-data-refresh,
 * FR-016, research R14).
 *
 * <p>Some sports (NBA) score during the week, so {@code last_scored_leg} is the
 * <i>current</i> week while it is being played. A week fetched then holds
 * partial transactions and points. Once the league moves on, the week is no
 * longer the last scored one, and the old "has rows" skip never refetched it --
 * measured on production NBA 2025 as week 10 holding 24 of 53 moves and weeks
 * 11-21 holding none.
 *
 * <p>A week is <b>final</b> iff it was fetched while {@code last_scored_leg}
 * was already past it. A non-final week is fetched on every refresh.
 *
 * <p>There is deliberately no "league status is complete" clause (amended after
 * code review, 2026-09-28): it froze a league's last scored week -- the
 * championship week -- at the first fetch after completion, before stat
 * corrections. The chain stops refreshing a season only after
 * {@code loaded_complete}, which waits for every per-game week to be final
 * (>= 48 h after the last real game), so the last fantasy week keeps being
 * refetched through exactly that correction window.
 */
public final class WeekFinality {

    private WeekFinality() {}

    public static boolean isFinal(int week, int lastScoredLegAtFetch) {
        if (week < 1) return false;
        return lastScoredLegAtFetch > week;
    }
}
