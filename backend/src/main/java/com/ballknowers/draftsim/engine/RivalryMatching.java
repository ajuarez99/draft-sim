package com.ballknowers.draftsim.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * The scoring and the exact matching behind the rivalry week (specs/034-rivalry-schedule-grid T010,
 * research R6, plan.md F12/F14). Pure: plain inputs, no Spring, no DB, so the preference ordering is
 * unit-tested without a fixture ({@code RivalryMatchingTest}, written first).
 *
 * <p><b>The score is ARBITRARY.</b> It is hand-set, not fitted (weights.yml {@code draftsim.rivalry}):
 *
 * <pre>
 *   closeness = m * (1 - |w - l| / (m + 1))      m = meetings; ties count toward m, not toward w or l
 *   score     = closeness + playoffBonus * playoffMeetings + championshipBonus * [met in a final]
 * </pre>
 *
 * The {@code +1} shrinks small samples: 1-0 scores 0.5, 1-1 scores 2, 2-0 scores 0.67, 5-5 scores 10, and
 * zero meetings score 0. Streak is deliberately NOT a term (F14): it is a reason a matched pair is
 * described by, not something the score chases.
 *
 * <p>Pairings are an exact maximum-weight perfect matching by bitmask DP, not a greedy pass (greedy is
 * order-sensitive and not optimal). Capped by the caller's {@code maxManagers}: above it the section is
 * refused, never approximated.
 *
 * <p>Spec 037 adds an {@code allowed} overload: a disallowed pair is simply not an edge, and when no full
 * matching exists the answer is empty rather than an exception. The unconstrained {@link #match(List,
 * PairScore, int)} delegates to it with every pair allowed, so spec 034's answer is unchanged
 * ({@code RivalryMatchingTest} passes untouched). In an odd league the sit-out is never constrained.
 */
public final class RivalryMatching {

    /** Differences at or below this are float noise, never a reason to prefer one matching (F12). */
    static final double EPS = 1e-9;

    private RivalryMatching() {}

    /**
     * One unordered pair's record. {@code playoffMeetings} counts winners-bracket elimination matches and
     * the final -- BRACKET MATCHES, not weeks, so a two-week final counts once (F3) -- and never placement
     * games (F4). {@code metInFinal} implies {@code playoffMeetings >= 1}.
     */
    public record PairStats(int meetings, int aWins, int bWins, int ties, int playoffMeetings, boolean metInFinal) {}

    public static double score(PairStats s, double playoffBonus, double championshipBonus) {
        int m = s.meetings();
        double closeness = m == 0 ? 0.0 : m * (1.0 - Math.abs(s.aWins() - s.bWins()) / (m + 1.0));
        return closeness + playoffBonus * s.playoffMeetings() + (s.metInFinal() ? championshipBonus : 0.0);
    }

    /** Score of an unordered pair, with {@code a < b}. */
    @FunctionalInterface
    public interface PairScore {
        double score(long a, long b);
    }

    /** {@code a < b} by id. */
    public record Pair(long a, long b, double score) {}

    /**
     * @param unpaired          the one manager left out of an odd league, else null
     * @param unpairedBestScore the best score that manager could have had with anyone else (0 when none);
     *                          the honest basis for "why", since the DP sits out whoever maximises the total
     *                          (F12), which is not necessarily whoever has the fewest rivalries. NaN when an
     *                          {@code allowed} filter leaves that manager no partner at all: there is no score
     *                          to quote, and 0 would read as a measured one
     */
    public record Matching(List<Pair> pairs, Long unpaired, double unpairedBestScore, double total) {}

    /**
     * Exact best matching.
     *
     * <p>Managers are sorted by ascending id; the DP state is the bitmask of who is used (F12). From a state
     * it takes the lowest free index {@code i}. For an odd league whose sit-out is not used yet (an even
     * popcount, since pairs consume two) it tries "{@code i} sits out" FIRST, then partners {@code j > i}
     * ascending. A candidate replaces the best only when it is greater by more than {@link #EPS}, so a tie
     * resolves to the first found and float noise cannot flip it. Same inputs, same output.
     *
     * @throws IllegalArgumentException for fewer than two managers, more than {@code maxManagers}, or a
     *                                  repeated id
     */
    public static Matching match(List<Long> managerIds, PairScore fn, int maxManagers) {
        return solve(managerIds, fn, null, maxManagers).orElseThrow();
    }

    /**
     * {@link #match(List, PairScore, int)} with a pair filter. {@code allowed} is asked once per unordered
     * pair (lower id first) and turned into a matrix up front, so the DP's inner loop does no calls.
     *
     * @return empty when no full matching exists with every chosen pair allowed (in an odd league: even with
     *         the best sit-out)
     */
    static Optional<Matching> match(List<Long> managerIds, PairScore fn, BiPredicate<Long, Long> allowed,
                                    int maxManagers) {
        long[] sorted = managerIds.stream().mapToLong(Long::longValue).sorted().toArray();
        boolean[][] ok = new boolean[sorted.length][sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            for (int j = i + 1; j < sorted.length; j++) ok[i][j] = allowed.test(sorted[i], sorted[j]);
        }
        return solve(managerIds, fn, ok, maxManagers);
    }

    /**
     * As above with the filter already a matrix over the ids sorted ascending ({@code allowed[i][j]} for
     * {@code i < j}); the week picker builds it once per week.
     */
    static Optional<Matching> match(List<Long> managerIds, PairScore fn, boolean[][] allowed, int maxManagers) {
        return solve(managerIds, fn, allowed, maxManagers);
    }

    private static Optional<Matching> solve(List<Long> managerIds, PairScore fn, boolean[][] allowed,
                                            int maxManagers) {
        int n = managerIds.size();
        if (n < 2) throw new IllegalArgumentException("a matching needs at least 2 managers, got " + n);
        if (n > maxManagers) {
            throw new IllegalArgumentException("a matching is capped at " + maxManagers + " managers, got " + n);
        }
        long[] ids = managerIds.stream().mapToLong(Long::longValue).sorted().toArray();
        for (int i = 1; i < n; i++) {
            if (ids[i] == ids[i - 1]) throw new IllegalArgumentException("duplicate manager id " + ids[i]);
        }
        double[][] s = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) s[i][j] = fn.score(ids[i], ids[j]);

        boolean odd = (n & 1) == 1;
        int full = (1 << n) - 1;
        double[] f = new double[full + 1];
        int[] choice = new int[full + 1];   // -1 = lowest free sits out, else its partner index
        Arrays.fill(f, Double.NEGATIVE_INFINITY);
        f[full] = 0.0;
        for (int mask = full - 1; mask >= 0; mask--) {
            int i = Integer.numberOfTrailingZeros(~mask);
            double best = Double.NEGATIVE_INFINITY;
            int pick = -2;
            if (odd && (Integer.bitCount(mask) & 1) == 0) {
                double cand = f[mask | (1 << i)];
                if (cand > best + EPS) { best = cand; pick = -1; }
            }
            for (int j = i + 1; j < n; j++) {
                if ((mask & (1 << j)) != 0) continue;
                if (allowed != null && !allowed[i][j]) continue;
                double rest = f[mask | (1 << i) | (1 << j)];
                if (rest == Double.NEGATIVE_INFINITY) continue;
                double cand = s[i][j] + rest;
                if (cand > best + EPS) { best = cand; pick = j; }
            }
            f[mask] = best;
            choice[mask] = pick;
        }

        if (f[0] == Double.NEGATIVE_INFINITY) return Optional.empty();   // never walk `choice` without a solution

        List<Pair> pairs = new ArrayList<>();
        Long unpaired = null;
        int unpairedIdx = -1;
        int mask = 0;
        while (mask != full) {
            int i = Integer.numberOfTrailingZeros(~mask);
            int pick = choice[mask];
            if (pick == -1) {
                unpaired = ids[i];
                unpairedIdx = i;
                mask |= 1 << i;
            } else {
                pairs.add(new Pair(ids[i], ids[pick], s[i][pick]));
                mask |= (1 << i) | (1 << pick);
            }
        }
        double unpairedBest = 0.0;
        if (unpairedIdx >= 0) {
            boolean anyAllowed = false;
            for (int j = 0; j < n; j++) {
                if (j == unpairedIdx) continue;
                if (allowed != null && !(unpairedIdx < j ? allowed[unpairedIdx][j] : allowed[j][unpairedIdx])) continue;
                anyAllowed = true;
                unpairedBest = Math.max(unpairedBest, unpairedIdx < j ? s[unpairedIdx][j] : s[j][unpairedIdx]);
            }
            if (!anyAllowed) unpairedBest = Double.NaN;
        }
        return Optional.of(new Matching(List.copyOf(pairs), unpaired, unpairedBest, f[0]));
    }
}
