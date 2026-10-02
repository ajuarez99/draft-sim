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
 *
 * <p><b>Amended 2026-10-02: the two sports read {@code last_scored_leg} differently.</b>
 * The rule above assumed {@code last_scored_leg} is the week being played (R14's
 * reading of NBA, reasoned, not measured mid-week). Measured on (Foot) Ball Knowers
 * 2026 on 2026-10-02: {@code leg} 4, {@code last_scored_leg} 3, with week 4 already
 * scoring (5 of 12 rosters had points after Thursday night). In NFL it is the last
 * <i>completed</i> week, so "{@code last_scored_leg > week}" held week 3 non-final
 * until week 4 finished: a week late, while Sleeper's own W-L already counted it.
 *
 * <p>So finality has two answers now:
 * <ul>
 *   <li>{@link #isFinal}: <b>counted as decided</b>. Sleeper has scored the week
 *       ({@code last_scored_leg >= week}) and moved on ({@code leg > week}), or the old
 *       clause holds. Where {@code last_scored_leg} tracks {@code leg} (NBA per R14,
 *       and every complete season: both read the last week) this is the old rule
 *       unchanged.</li>
 *   <li>{@link #mayStopRefetching}: <b>frozen</b>. A final week is still refetched while
 *       it is the last scored week, so an NFL stat correction made during the following
 *       week is still picked up, exactly as before this amendment.</li>
 * </ul>
 */
public final class WeekFinality {

    private WeekFinality() {}

    /**
     * @param legAtFetch Sleeper's {@code settings.leg} at fetch time; 0 when absent,
     *                   which falls back to the original rule
     */
    public static boolean isFinal(int week, int lastScoredLegAtFetch, int legAtFetch) {
        if (week < 1) return false;
        if (lastScoredLegAtFetch > week) return true;
        return legAtFetch > week && lastScoredLegAtFetch >= week;
    }

    /** Whether a stored week may be skipped: final, and no longer the last scored week. */
    public static boolean mayStopRefetching(boolean storedFinal, int week, int lastScoredLegNow) {
        return storedFinal && week < lastScoredLegNow;
    }
}
