package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.engine.RealizedLineupService;
import com.ballknowers.draftsim.historical.HistoricalResult.Bust;
import com.ballknowers.draftsim.historical.HistoricalResult.Hindsight;
import com.ballknowers.draftsim.historical.HistoricalResult.HindsightPick;
import com.ballknowers.draftsim.historical.HistoricalResult.Steal;
import com.ballknowers.draftsim.historical.SeasonReplay.Pick;
import com.ballknowers.draftsim.historical.SeasonReplay.PlayerWeeks;
import com.ballknowers.draftsim.historical.SeasonReplay.TeamScorer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The after-the-fact comparison panel (spec 053 US2): the hindsight draft, the biggest bust and the best steal
 * passed on. Pure: functions of the replayed player-week scores.
 *
 * <p>Season value of a player = the sum of his weekly lock-in scores ({@link PlayerWeeks#seasonValue}). None of
 * these is "optimal"; each says plainly what it is.
 */
public final class Comparisons {

    /** Shown on the page next to the hindsight total: it is greedy, and the bots are not re-drafted. */
    public static final String HINDSIGHT_LABEL = "greedy hindsight, bots unchanged";

    private static final double EPS = 1e-9;

    private final RealizedLineupService lineups;

    public Comparisons(RealizedLineupService lineups) {
        this.lineups = lineups;
    }

    public record Computed(Hindsight hindsight, Bust bust, Steal steal) {}

    /** Runs all three from a replay's input and its already-scored team machinery. */
    Computed compute(SeasonReplay.Input in, TeamScorer team, double userPointsFor) {
        PlayerWeeks pw = team.scores();
        List<Pick> userPicks = in.picks().stream().filter(p -> p.slot() == in.userSlot()).toList();

        Map<Long, Double> value = new HashMap<>();
        for (long id : in.cards().keySet()) value.put(id, pw.seasonValue(id));

        Set<Long> draftedIds = new HashSet<>();
        for (Pick p : in.picks()) draftedIds.add(p.playerId());
        Map<Long, Integer> rank = valueRanks(draftedIds, value);

        Bust bust = bust(userPicks, rank, value, in.cards());
        Steal steal = steal(userPicks, in.picks(), in.cards().keySet(), value, in.cards());
        Hindsight hindsight = hindsight(userPicks.stream().map(Pick::pickNo).sorted().toList(), in.picks(),
                in.cards().keySet(), team, in.weekCount(), value, in.cards(), userPointsFor);
        return new Computed(hindsight, bust, steal);
    }

    /** 1 = the highest season value; ties by player id ascending. Among {@code ids} only. */
    static Map<Long, Integer> valueRanks(Collection<Long> ids, Map<Long, Double> value) {
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingDouble((Long id) -> value.getOrDefault(id, 0.0)).reversed()
                .thenComparingLong(Long::longValue));
        Map<Long, Integer> out = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) out.put(sorted.get(i), i + 1);
        return out;
    }

    /**
     * Bust = argmax over the user's picks of (season-value rank - overall pick number): the pick taken furthest
     * ahead of where he finished. Null when no pick finished below where it was taken (plan F1). The test that
     * pins the sign: pick 3 finishing 150th is a bust; pick 160 finishing 2nd never is.
     *
     * @param rank rank among every drafted player, 1 = best
     */
    static Bust bust(List<Pick> userPicks, Map<Long, Integer> rank, Map<Long, Double> value,
                     Map<Long, HistoricalPlayerCard> cards) {
        Pick worst = null;
        int worstGap = 0;
        // Earliest pick first, so a tie on the gap goes to the earlier pick.
        for (Pick p : userPicks.stream().sorted(Comparator.comparingInt(Pick::pickNo)).toList()) {
            int gap = rank.get(p.playerId()) - p.pickNo();
            if (gap > worstGap) {
                worst = p;
                worstGap = gap;
            }
        }
        if (worst == null) return null;
        return new Bust(worst.pickNo(), cards.get(worst.playerId()), rank.get(worst.playerId()),
                value.get(worst.playerId()));
    }

    /**
     * Best steal passed on = over the user's picks, the player available at that pick (not yet taken in the real
     * draft) whom the user never drafted at all, with the largest value above the player the user took. A player
     * the user took later is excluded (plan F19). Null when nobody beat what the user took.
     */
    static Steal steal(List<Pick> userPicks, List<Pick> allPicks, Collection<Long> boardIds,
                       Map<Long, Double> value, Map<Long, HistoricalPlayerCard> cards) {
        Set<Long> mine = new HashSet<>();
        for (Pick p : userPicks) mine.add(p.playerId());

        List<Long> sortedIds = new ArrayList<>(boardIds);
        java.util.Collections.sort(sortedIds);   // lowest id wins an exact tie
        Steal best = null;
        double bestGap = 0;
        for (Pick up : userPicks.stream().sorted(Comparator.comparingInt(Pick::pickNo)).toList()) {
            Set<Long> takenBefore = new HashSet<>();
            for (Pick p : allPicks) if (p.pickNo() < up.pickNo()) takenBefore.add(p.playerId());
            double takenValue = value.get(up.playerId());
            long bestId = -1;
            double bestHere = 0;
            for (long id : sortedIds) {
                if (takenBefore.contains(id) || mine.contains(id)) continue;
                double gap = value.get(id) - takenValue;
                if (gap > bestHere + EPS) {
                    bestHere = gap;
                    bestId = id;
                }
            }
            if (bestId >= 0 && bestHere > bestGap + EPS) {
                bestGap = bestHere;
                best = new Steal(up.pickNo(), cards.get(up.playerId()), takenValue, cards.get(bestId),
                        value.get(bestId));
            }
        }
        return best;
    }

    /**
     * The greedy hindsight draft (plan F4). At each of the user's pick numbers, in order, take the candidate that
     * maximises the hindsight team's own SEASON best-ball total: lineup-aware, so a fourth centre who never
     * starts loses to a starter. Candidates are the players still available at that pick in the REAL draft,
     * minus the hindsight team's earlier picks; a player a bot took later is allowed. The bots' picks are not
     * changed, and the hindsight team is scored on its own, never against re-drafted rivals.
     *
     * <p>Exact, and fast, by branch and bound: a candidate can add at most the sum of his positive weekly
     * scores to any roster, so candidates are tried in that order and the search stops once no remaining
     * candidate can beat the best found. Ties break on season value, then player id.
     *
     * @param userPointsFor the user's own season total, for {@code userBeatIt}
     */
    Hindsight hindsight(List<Integer> userPickNos, List<Pick> allPicks, Collection<Long> boardIds,
                        TeamScorer team, int weekCount, Map<Long, Double> value,
                        Map<Long, HistoricalPlayerCard> cards, double userPointsFor) {
        PlayerWeeks pw = team.scores();
        Set<Long> chosen = new LinkedHashSet<>();
        List<HindsightPick> picks = new ArrayList<>();
        double baseTotal = 0;
        // Each week's total is rounded to cents, so the bound below carries that much slack per week.
        double slack = 0.01 * weekCount + EPS;

        for (int pickNo : userPickNos) {
            Set<Long> unavailable = new HashSet<>(chosen);
            for (Pick p : allPicks) if (p.pickNo() < pickNo) unavailable.add(p.playerId());

            List<Long> candidates = new ArrayList<>();
            for (long id : boardIds) if (!unavailable.contains(id)) candidates.add(id);
            candidates.sort(Comparator.comparingDouble((Long id) -> pw.positiveValue(id)).reversed()
                    .thenComparingLong(Long::longValue));

            long bestId = -1;
            double bestTotal = Double.NEGATIVE_INFINITY;
            for (long id : candidates) {
                if (bestId >= 0 && baseTotal + pw.positiveValue(id) + slack < bestTotal) break;
                List<Long> trial = new ArrayList<>(chosen);
                trial.add(id);
                double total = team.season(trial, weekCount);
                if (bestId < 0 || total > bestTotal + EPS
                        || (Math.abs(total - bestTotal) <= EPS && better(id, bestId, value))) {
                    bestId = id;
                    bestTotal = total;
                }
            }
            if (bestId < 0) throw new IllegalStateException("no candidate available at pick " + pickNo);
            chosen.add(bestId);
            baseTotal = bestTotal;
            picks.add(new HindsightPick(pickNo, cards.get(bestId), value.get(bestId)));
        }
        double total = Math.round(team.season(chosen, weekCount) * 100.0) / 100.0;
        return new Hindsight(HINDSIGHT_LABEL, total, userPointsFor, total < userPointsFor, picks);
    }

    /** Tie-break between equal-total candidates: higher season value, then lower id. */
    private static boolean better(long a, long b, Map<Long, Double> value) {
        double va = value.get(a), vb = value.get(b);
        if (Math.abs(va - vb) > EPS) return va > vb;
        return a < b;
    }
}
