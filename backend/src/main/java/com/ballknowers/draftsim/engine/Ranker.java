package com.ballknowers.draftsim.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Standard competition ranking (1, 2, 2, 4), direction supplied by the
 * caller's {@link Comparator} -- shared by {@link PowerRankingService}'s
 * descending computed-score ranking and {@link MemberRankingService}'s
 * ascending average-ballot-rank ranking, so both resolve ties the same way
 * rather than growing two subtly different copies of the same rule.
 *
 * <p><b>claude/plan-review-power-rankings-ballots.md finding 6, and the
 * reason this class exists as its own file instead of being inlined
 * twice.</b> An earlier shape of {@code PowerRankingService.rankDescending}
 * detected a tie by {@code rounded < prevRounded} -- correct for a
 * descending sort, but silently DIRECTION-COUPLED with it. Parameterize only
 * the *sort* with a comparator and leave that {@code <} in place, and the
 * ascending case (lower is better, which is exactly what an average ballot
 * rank needs) inverts: {@code rounded} increases every step,
 * {@code rounded < prevRounded} is never true again after the first element,
 * and {@code rank} never advances past 1 -- every roster in a member
 * aggregate would come back rank 1. Structurally perfect (right count, no
 * duplicates, no crash), pointed backwards -- claude/lessons.md #1's shape
 * verbatim. The tie test below is equality on the rounded key
 * ({@code !rounded.equals(prevRounded)}), which has no direction of its own,
 * so it cannot invert regardless of which way {@code order} sorts.
 *
 * <p>Rounding to 4dp before comparing exists to absorb float noise between
 * two scores that are "equal" up to double-precision error and would print
 * identically -- it happens to match {@code power_ranking_entry.score}'s
 * stored column precision for {@link PowerRankingService}'s callers, but
 * that is a coincidence of this being the first caller, not a contract this
 * class has with the database: {@link MemberRankingService}'s average ranks
 * are never stored at all.
 */
final class Ranker {

    private Ranker() {}

    record Ranked<T>(T item, int rank, double score) {}

    static <T> List<Ranked<T>> rank(List<T> items, Comparator<T> order, ToDoubleFunction<T> scoreOf) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(order);

        List<Ranked<T>> out = new ArrayList<>(sorted.size());
        int rank = 0, seen = 0;
        Double prevRounded = null;
        for (T item : sorted) {
            seen++;
            double rounded = Math.round(scoreOf.applyAsDouble(item) * 10000.0) / 10000.0;
            if (prevRounded == null || !Double.valueOf(rounded).equals(prevRounded)) {
                rank = seen;
                prevRounded = rounded;
            }
            out.add(new Ranked<>(item, rank, rounded));
        }
        return out;
    }
}
