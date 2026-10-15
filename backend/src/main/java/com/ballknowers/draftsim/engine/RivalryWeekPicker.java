package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RivalryMatching.Matching;
import com.ballknowers.draftsim.engine.RivalryMatching.PairScore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which week should be rivalry week, and with which pairings, under the league's meeting cap
 * (specs/037-rivalry-week-pick, research R3/R4, plan.md "Amended after review"). Pure: plain inputs, no
 * Spring, no DB, so the constraint and the preference ordering are unit-tested without a fixture
 * ({@code RivalryWeekPickerTest}, written first).
 *
 * <p><b>The cap is a rule, not a weight.</b> For each eligible week W the exact spec 034 matching runs with
 * every pair that would reach a third meeting removed. {@code reg} counts a pair's regular-season meetings in
 * the published schedule; putting the pair in W replaces its W game (if any), so its count becomes
 * {@code reg - inW + 1}, and the pair is <b>allowed iff that is {@code <= cap}</b>. A pair already above the cap
 * is therefore not allowed in any week, including its own (F6). A pair that is not chosen never has its count
 * raised, so the cap only needs checking on the chosen pairs.
 *
 * <p><b>Ranking (F2, F11)</b>, on the DISPLAYED total {@code round3(total)} so the sort and the tie rule agree:
 * (1) total, descending; (2) history-best pairs kept ({@code |matching.pairs ∩ unconstrained.pairs|}),
 * descending; (3) week, ascending. Infeasible weeks (no full cap-obeying matching) sort after every feasible
 * one, by week. The spec 034 score is not touched here.
 */
public final class RivalryWeekPicker {

    private RivalryWeekPicker() {}

    /** An unordered manager pair, {@code a < b}. */
    public record PairId(long a, long b) {
        static PairId of(long x, long y) {
            return new PairId(Math.min(x, y), Math.max(x, y));
        }
    }

    /** A pair the published schedule already has above the cap. */
    public record OverCap(long a, long b, int meetings) {}

    /**
     * One eligible week.
     *
     * @param matching    null when infeasible
     * @param total       0 when infeasible; the matching's total otherwise
     * @param heldBack    pairs of the unconstrained matching that are not allowed in this week
     * @param displaced   this week's scheduled pairs that are not in the matching, in (a, b) order
     * @param alreadyIn   pairs in the matching that are scheduled this week anyway
     * @param keptBest    how many pairs the matching shares with the unconstrained matching
     * @param sameAsBest  the matching's pairs equal the unconstrained matching's pairs (F1)
     * @param tiedWith    feasible weeks equal on rank keys 1 AND 2 (this one included); 0 when infeasible
     */
    public record Candidate(int week, boolean feasible, Matching matching, double total, List<PairId> heldBack,
                            List<PairId> displaced, Set<PairId> alreadyIn, int keptBest, boolean sameAsBest,
                            int tiedWith) {}

    /**
     * @param unconstrained spec 034's history-best matching
     * @param candidates    one per eligible week, in {@link #RANK} order
     * @param recommended   the first candidate when it is feasible, else null
     * @param overCapPairs  pairs the published schedule already has above the cap
     */
    public record WeekPick(Matching unconstrained, List<Candidate> candidates, Candidate recommended,
                           List<OverCap> overCapPairs) {}

    /** Rounds to the three decimals the page displays. */
    static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    /** Feasible first; then round3(total) desc, kept pairs desc, week asc. Infeasible by week. */
    public static final Comparator<Candidate> RANK = (x, y) -> {
        if (x.feasible() != y.feasible()) return x.feasible() ? -1 : 1;
        if (!x.feasible()) return Integer.compare(x.week(), y.week());
        int c = Double.compare(round3(y.total()), round3(x.total()));
        if (c != 0) return c;
        c = Integer.compare(y.keptBest(), x.keptBest());
        if (c != 0) return c;
        return Integer.compare(x.week(), y.week());
    };

    /** Solves the unconstrained matching itself; prefer the overload that takes spec 034's already-solved one. */
    public static WeekPick pick(List<Long> managerIds, Map<Integer, List<long[]>> schedule, List<Integer> eligibleWeeks,
                                int cap, PairScore fn, int maxManagers) {
        return pick(managerIds, schedule, eligibleWeeks, cap, fn, maxManagers,
                RivalryMatching.match(managerIds, fn, maxManagers));
    }

    /**
     * @param schedule      regular-season week to scheduled manager pairs; null managers and self-pairs already
     *                      dropped by the caller
     * @param eligibleWeeks ascending
     * @param unconstrained {@code RivalryMatching.match(managerIds, fn, maxManagers)}, solved once by the caller
     */
    public static WeekPick pick(List<Long> managerIds, Map<Integer, List<long[]>> schedule, List<Integer> eligibleWeeks,
                                int cap, PairScore fn, int maxManagers, Matching unconstrained) {
        long[] ids = managerIds.stream().mapToLong(Long::longValue).sorted().toArray();
        int n = ids.length;

        Map<PairId, Integer> reg = new HashMap<>();
        for (List<long[]> week : schedule.values()) {
            for (long[] p : week) reg.merge(PairId.of(p[0], p[1]), 1, Integer::sum);
        }
        List<OverCap> overCap = new ArrayList<>();
        for (Map.Entry<PairId, Integer> e : reg.entrySet()) {
            if (e.getValue() > cap) overCap.add(new OverCap(e.getKey().a(), e.getKey().b(), e.getValue()));
        }
        overCap.sort(Comparator.comparingLong(OverCap::a).thenComparingLong(OverCap::b));

        Set<PairId> best = new LinkedHashSet<>();
        for (RivalryMatching.Pair p : unconstrained.pairs()) best.add(new PairId(p.a(), p.b()));

        List<Candidate> raw = new ArrayList<>();
        for (int week : eligibleWeeks) {
            Set<PairId> inW = new HashSet<>();
            TreeSet<PairId> scheduledInW = new TreeSet<>(
                    Comparator.comparingLong(PairId::a).thenComparingLong(PairId::b));
            for (long[] p : schedule.getOrDefault(week, List.of())) {
                PairId id = PairId.of(p[0], p[1]);
                inW.add(id);
                scheduledInW.add(id);
            }
            boolean[][] allowed = new boolean[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    PairId id = new PairId(ids[i], ids[j]);
                    allowed[i][j] = reg.getOrDefault(id, 0) - (inW.contains(id) ? 1 : 0) + 1 <= cap;
                }
            }
            List<PairId> heldBack = new ArrayList<>();
            for (PairId b : best) {
                int i = java.util.Arrays.binarySearch(ids, b.a());
                int j = java.util.Arrays.binarySearch(ids, b.b());
                if (!allowed[i][j]) heldBack.add(b);
            }
            var solved = RivalryMatching.match(managerIds, fn, allowed, maxManagers);
            if (solved.isEmpty()) {
                raw.add(new Candidate(week, false, null, 0.0, List.copyOf(heldBack), List.copyOf(scheduledInW),
                        Set.of(), 0, false, 0));
                continue;
            }
            Matching m = solved.get();
            Set<PairId> chosen = new LinkedHashSet<>();
            for (RivalryMatching.Pair p : m.pairs()) chosen.add(new PairId(p.a(), p.b()));
            int kept = 0;
            for (PairId c : chosen) if (best.contains(c)) kept++;
            Set<PairId> already = new LinkedHashSet<>();
            List<PairId> displaced = new ArrayList<>();
            for (PairId s : scheduledInW) {
                if (chosen.contains(s)) already.add(s);
                else displaced.add(s);
            }
            boolean same = kept == best.size() && chosen.size() == best.size();
            raw.add(new Candidate(week, true, m, m.total(), List.copyOf(heldBack), List.copyOf(displaced),
                    Set.copyOf(already), kept, same, 0));
        }

        // tiedWith: feasible weeks equal on rank keys 1 and 2.
        Map<String, Integer> groupSize = new HashMap<>();
        for (Candidate c : raw) if (c.feasible()) groupSize.merge(tieKey(c), 1, Integer::sum);
        List<Candidate> ranked = new ArrayList<>();
        for (Candidate c : raw) {
            ranked.add(c.feasible()
                    ? new Candidate(c.week(), true, c.matching(), c.total(), c.heldBack(), c.displaced(),
                            c.alreadyIn(), c.keptBest(), c.sameAsBest(), groupSize.get(tieKey(c)))
                    : c);
        }
        ranked.sort(RANK);
        Candidate recommended = !ranked.isEmpty() && ranked.get(0).feasible() ? ranked.get(0) : null;
        return new WeekPick(unconstrained, List.copyOf(ranked), recommended, List.copyOf(overCap));
    }

    /** Rank keys 1 and 2; candidates with the same key are one tie group. */
    static String tieKey(Candidate c) {
        return round3(c.total()) + "|" + c.keptBest();
    }
}
