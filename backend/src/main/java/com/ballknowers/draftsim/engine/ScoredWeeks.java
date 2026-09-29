package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.LeagueWeekFetchRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Which weeks of a league are stored, and which of those are FINAL
 * (claude/audit-2026-09-28/10; the finality rule itself is spec 009's
 * {@code WeekFinality}, recorded in {@code league_week_fetch}).
 *
 * <p>Stored is not final: NBA scores during the week, so the current week is stored
 * partial and keeps changing. Pages that say "N weeks scored" or default to "the latest
 * week" mean final; a page that lets someone look at an in-progress week on purpose
 * uses the stored bound.
 *
 * <p><b>When there is no finality information, every stored week is final.</b> Two cases,
 * both matching how 009 itself behaves:
 * <ul>
 *   <li>The season is {@code loaded_complete}. 009 never fetches it again, and the last
 *       fantasy week (the championship) can never satisfy {@code leg > week}, so its
 *       POINTS row stays non-final forever. Reading that literally would leave a finished
 *       season with a permanently "in progress" last week.</li>
 *   <li>The league has no POINTS rows in {@code league_week_fetch} at all: ingested
 *       before 009, or never refreshed since. Nothing says any week is partial, and
 *       treating everything as in progress would hide a whole old season. The cost: a
 *       pre-009 season still being played reads as final until its next refresh
 *       writes rows.</li>
 * </ul>
 */
@Component
public class ScoredWeeks {

    /** {@code finalWeeks} is always a subset of the stored weeks. */
    public record Snapshot(int latestStored, int latestFinal, Set<Integer> finalWeeks) {
        public boolean isFinal(int week) {
            return finalWeeks.contains(week);
        }
    }

    private final RosterWeekPointsRepository weekPoints;
    private final LeagueWeekFetchRepository weekFetches;
    private final LeagueRefreshRepository refreshes;

    public ScoredWeeks(RosterWeekPointsRepository weekPoints, LeagueWeekFetchRepository weekFetches,
                       LeagueRefreshRepository refreshes) {
        this.weekPoints = weekPoints;
        this.weekFetches = weekFetches;
        this.refreshes = refreshes;
    }

    public Snapshot of(long leagueId) {
        Set<Integer> stored = weekPoints.storedWeeks(leagueId);
        if (stored.isEmpty()) return new Snapshot(0, 0, Set.of());

        boolean complete = refreshes.find(leagueId).map(LeagueRefreshRepository.Row::loadedComplete).orElse(false);
        Set<Integer> finals;
        if (complete || !weekFetches.hasAny(leagueId, LeagueWeekFetchRepository.POINTS)) {
            finals = new HashSet<>(stored);
        } else {
            finals = new HashSet<>(weekFetches.finalWeeks(leagueId, LeagueWeekFetchRepository.POINTS));
            finals.retainAll(stored);
        }
        return new Snapshot(max(stored), max(finals), finals);
    }

    private static int max(Set<Integer> s) {
        return s.stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
