package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.engine.RealizedLineupService;
import com.ballknowers.draftsim.historical.HistoricalResult.Bust;
import com.ballknowers.draftsim.historical.HistoricalResult.Hindsight;
import com.ballknowers.draftsim.historical.HistoricalResult.HindsightPick;
import com.ballknowers.draftsim.historical.HistoricalResult.PickFinish;
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

    public record Computed(Hindsight hindsight, Bust bust, Steal steal, List<PickFinish> pickFinishes) {}

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
        Map<Long, Integer> weeksPlayed = new HashMap<>();
        for (long id : in.cards().keySet()) weeksPlayed.put(id, pw.weeksPlayed(id));
        return new Computed(hindsight, bust, steal,
                pickFinishes(userPicks, in.picks(), in.cards(), value, weeksPlayed));
    }

    /**
     * Spec 065: for each of the user's picks, "drafted as the Nth at his position, finished as the Mth".
     *
     * <p>Drafted rank counts picks by EVERY team in this game's draft, in pick order, at the player's primary
     * position (the first on his card). Finish rank is by season value among every board player with that primary
     * position, drafted or not, so an undrafted breakout pushes a drafted player down, as it would in a real
     * season. Level values share a rank: 1 + the number who scored strictly more. Lower is better for both; the
     * test that pins the direction is the second RB taken who scored the most finishing 1, not last.
     */
    static List<PickFinish> pickFinishes(List<Pick> userPicks, List<Pick> allPicks,
                                         Map<Long, HistoricalPlayerCard> cards, Map<Long, Double> value,
                                         Map<Long, Integer> weeksPlayed) {
        Map<String, List<Double>> valuesByPosition = new HashMap<>();
        for (HistoricalPlayerCard c : cards.values()) {
            valuesByPosition.computeIfAbsent(primary(c), k -> new ArrayList<>()).add(value.getOrDefault(c.id(), 0.0));
        }
        Map<Long, Integer> draftedRank = new HashMap<>();
        Map<String, Integer> takenSoFar = new HashMap<>();
        for (Pick p : allPicks.stream().sorted(Comparator.comparingInt(Pick::pickNo)).toList()) {
            draftedRank.put(p.playerId(), takenSoFar.merge(primary(cards.get(p.playerId())), 1, Integer::sum));
        }

        List<PickFinish> out = new ArrayList<>();
        for (Pick p : userPicks.stream().sorted(Comparator.comparingInt(Pick::pickNo)).toList()) {
            HistoricalPlayerCard card = cards.get(p.playerId());
            String pos = primary(card);
            double mine = value.getOrDefault(p.playerId(), 0.0);
            List<Double> pool = valuesByPosition.get(pos);
            int ahead = 0;
            for (double v : pool) if (v > mine + EPS) ahead++;
            out.add(new PickFinish(p.pickNo(), card, pos, draftedRank.get(p.playerId()), ahead + 1, pool.size(), mine,
                    weeksPlayed.getOrDefault(p.playerId(), 0)));
        }
        return out;
    }

    private static String primary(HistoricalPlayerCard c) {
        return c.positions() == null || c.positions().isEmpty() ? "" : c.positions().get(0);
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
     * The greedy hindsight draft (plan F4). At each of the user's pick numbers take the candidate that
     * maximises the hindsight team's own SEASON best-ball total: lineup-aware, so a fourth centre who never
     * starts loses to a starter. Candidates are the players still available at that pick in the REAL draft,
     * minus the hindsight team's other picks; a player a bot took later is allowed. The bots' picks are not
     * changed, and the hindsight team is scored on its own, never against re-drafted rivals.
     *
     * <p>Two passes, and the higher total is kept (Allan 2026-11-15). The first walks the picks first to last,
     * as it always has. That one spends an early pick on a player who was still there many rounds later (it
     * took a fifth-round pick for a player the user really got in the eleventh), so the second walks the picks
     * LAST to first: a late pick is filled from what was left at that point, and the early picks are kept for
     * the players who were only there early. With plain additive values the backward walk is the optimal
     * answer; with lineups it is still a greedy, so the forward result is kept when it scored more. The label
     * says greedy either way.
     *
     * <p>The roster is then laid out so every player sits at the LATEST pick he could still be had at
     * ({@link #layOutLate}), which is what a drafter with hindsight would do and what the page shows.
     *
     * <p>Each step is exact, and fast, by branch and bound: a candidate can add at most the sum of his positive
     * weekly scores to any roster, so candidates are tried in that order and the search stops once no remaining
     * candidate can beat the best found. Ties break on season value, then player id.
     *
     * @param userPickNos   the user's pick numbers, ascending
     * @param userPointsFor the user's own season total, for {@code userBeatIt}
     */
    Hindsight hindsight(List<Integer> userPickNos, List<Pick> allPicks, Collection<Long> boardIds,
                        TeamScorer team, int weekCount, Map<Long, Double> value,
                        Map<Long, HistoricalPlayerCard> cards, double userPointsFor) {
        Map<Long, Integer> takenAt = new HashMap<>();
        for (Pick p : allPicks) takenAt.put(p.playerId(), p.pickNo());

        Set<Long> forward = greedy(userPickNos, takenAt, boardIds, team, weekCount, value);
        Set<Long> backward = greedy(userPickNos.reversed(), takenAt, boardIds, team, weekCount, value);
        double forwardTotal = team.season(forward, weekCount);
        double backwardTotal = team.season(backward, weekCount);
        Set<Long> chosen = backwardTotal > forwardTotal + EPS ? backward : forward;

        List<HindsightPick> picks = new ArrayList<>();
        Map<Integer, Long> slots = layOutLate(userPickNos, chosen, takenAt, value);
        for (int pickNo : userPickNos) {
            long id = slots.get(pickNo);
            picks.add(new HindsightPick(pickNo, cards.get(id), value.get(id)));
        }
        double total = Math.round(team.season(chosen, weekCount) * 100.0) / 100.0;
        return new Hindsight(HINDSIGHT_LABEL, total, userPointsFor, total < userPointsFor, picks);
    }

    /**
     * (Package-visible since spec 074: the season page's dream roster calls it with nothing taken, so every board
     * player is a candidate at every step.)
     *
     * <p>One greedy pass over {@code order} (the user's pick numbers, in the order to fill them). A player is
     * available at a pick when nobody took him at an EARLIER pick of the real draft; the user's own pick of him
     * counts, so a player the user took at pick 127 can be had at 127 or before, never after.
     */
    Set<Long> greedy(List<Integer> order, Map<Long, Integer> takenAt, Collection<Long> boardIds,
                     TeamScorer team, int weekCount, Map<Long, Double> value) {
        PlayerWeeks pw = team.scores();
        Set<Long> chosen = new LinkedHashSet<>();
        double baseTotal = 0;
        // Each week's total is rounded to cents, so the bound below carries that much slack per week.
        double slack = 0.01 * weekCount + EPS;

        for (int pickNo : order) {
            List<Long> candidates = new ArrayList<>();
            for (long id : boardIds) {
                if (chosen.contains(id)) continue;
                Integer taken = takenAt.get(id);
                if (taken == null || taken >= pickNo) candidates.add(id);
            }
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
        }
        return chosen;
    }

    /**
     * Seats a hindsight roster on the user's picks, every player as late as he could still be had. Walking the
     * picks last to first, each takes the unseated player who was gone soonest after it (the smallest real pick
     * number at or after this one; a player nobody drafted counts as never gone). Among players gone at the same
     * point, the lower season value goes later, so the better player reads as the earlier pick. Any choice among
     * the players available at a pick leaves the rest seatable, because whoever is available at a pick was
     * available at every earlier one, so this cannot paint itself into a corner.
     *
     * <p>A player the user drafted was "gone" at the user's own pick, so he lands on exactly that pick whenever
     * the roster allows it, and the page can say "same pick" instead of charging hindsight an earlier one.
     */
    static Map<Integer, Long> layOutLate(List<Integer> userPickNos, Collection<Long> roster,
                                         Map<Long, Integer> takenAt, Map<Long, Double> value) {
        List<Long> open = new ArrayList<>(roster);
        Map<Integer, Long> out = new HashMap<>();
        for (int pickNo : userPickNos.stream().sorted(Comparator.reverseOrder()).toList()) {
            long pick = -1;
            for (long id : open) {
                int gone = takenAt.getOrDefault(id, Integer.MAX_VALUE);
                if (gone < pickNo) continue;
                if (pick < 0) {
                    pick = id;
                    continue;
                }
                int bestGone = takenAt.getOrDefault(pick, Integer.MAX_VALUE);
                double v = value.getOrDefault(id, 0.0), bv = value.getOrDefault(pick, 0.0);
                if (gone < bestGone || (gone == bestGone && (v < bv - EPS || (Math.abs(v - bv) <= EPS && id > pick)))) {
                    pick = id;
                }
            }
            if (pick < 0) throw new IllegalStateException("no hindsight player can be seated at pick " + pickNo);
            open.remove(Long.valueOf(pick));
            out.put(pickNo, pick);
        }
        return out;
    }

    /** Tie-break between equal-total candidates: higher season value, then lower id. */
    private static boolean better(long a, long b, Map<Long, Double> value) {
        double va = value.get(a), vb = value.get(b);
        if (Math.abs(va - vb) > EPS) return va > vb;
        return a < b;
    }
}
