package com.ballknowers.draftsim.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The weekly high-score pot as arithmetic (specs/048-weekly-high-score-ledger): each decided week's top
 * scorer collects the stake from every other roster that has a score row that week. Pure and static -- no
 * Spring, no DB -- so the zero-sum and settle-up invariants can be property-tested.
 *
 * <p>Everything is integer cents, so "the balances sum to zero" is exact, with no rounding step to get wrong.
 * Scores are compared with {@link BigDecimal#compareTo}, never {@code equals} (150.1 and 150.10 tie).
 *
 * <p><b>Ties.</b> With {@code k} co-winners and stake {@code s}, each payer pays {@code s} in total: every
 * co-winner is owed {@code s / k} from that payer, and the first {@code s % k} co-winners in ascending
 * rosterId get one extra cent. Ascending rosterId is a house convention, not a league rule, and the payload
 * says so ({@code tieCentRule}).
 *
 * <p><b>Two settle-up lists, both clearing every balance.</b> {@code pairwise} nets each unordered pair of
 * rosters (what each owes the other across the season). {@code shortcut} is greedy per connected component
 * of the pairwise debt graph: repeatedly match the most-negative and most-positive balance in the component
 * and transfer the smaller magnitude. <i>Proof the shortcut is never longer than pairwise, per component:</i>
 * a component of m rosters is connected, so the pairwise graph has at least m - 1 edges. Each greedy transfer
 * zeroes at least one balance, and the last transfer in a component zeroes two (the balances sum to zero), so
 * a component with n non-zero balances takes at most n - 1 transfers, and n is at most m. Greedy is not
 * claimed to be the minimum (that is NP-hard in general). Equal balances are tie-broken by rosterId ascending.
 */
public final class WeeklyPotLedger {

    private WeeklyPotLedger() {}

    public record Score(int week, int rosterId, BigDecimal points) {}

    /** {@code winners} is ascending rosterId; {@code runnerUpPoints} is null when every roster tied. */
    public record WeekResult(int week, List<Integer> winners, BigDecimal winningPoints, BigDecimal runnerUpPoints,
                             int payers, long potCents) {}

    public record Balance(int rosterId, int weeksWon, long collectedCents, long paidCents, long netCents) {}

    public record Debt(int fromRosterId, int toRosterId, long cents) {}

    public record Ledger(List<WeekResult> weeks, Map<Integer, Balance> balances, List<Debt> pairwise,
                         List<Debt> shortcut) {}

    /**
     * @param scores       DECIDED weeks only; the caller owns the finality and regular-season rules
     * @param allRosterIds every roster that should appear in {@code balances}, zeros allowed
     */
    public static Ledger compute(List<Score> scores, Collection<Integer> allRosterIds, long stakeCents) {
        Map<Integer, List<Score>> byWeek = new TreeMap<>();
        for (Score s : scores) byWeek.computeIfAbsent(s.week(), w -> new ArrayList<>()).add(s);

        TreeSet<Integer> ids = new TreeSet<>(allRosterIds);
        for (Score s : scores) ids.add(s.rosterId());

        Map<Integer, Integer> weeksWon = new HashMap<>();
        Map<Integer, Long> collected = new HashMap<>();
        Map<Integer, Long> paid = new HashMap<>();
        // owed.get(x).get(y): cents x owes y across the season
        Map<Integer, Map<Integer, Long>> owed = new HashMap<>();
        List<WeekResult> weeks = new ArrayList<>();

        for (Map.Entry<Integer, List<Score>> e : byWeek.entrySet()) {
            List<Score> rows = e.getValue();
            BigDecimal max = null;
            for (Score s : rows) if (max == null || s.points().compareTo(max) > 0) max = s.points();
            List<Integer> winners = new ArrayList<>();
            List<Integer> payers = new ArrayList<>();
            BigDecimal runnerUp = null;
            for (Score s : rows) {
                if (s.points().compareTo(max) == 0) {
                    winners.add(s.rosterId());
                } else {
                    payers.add(s.rosterId());
                    if (runnerUp == null || s.points().compareTo(runnerUp) > 0) runnerUp = s.points();
                }
            }
            winners.sort(Comparator.naturalOrder());
            int k = winners.size();
            long base = stakeCents / k;
            long extra = stakeCents % k;
            for (int payer : payers) {
                paid.merge(payer, stakeCents, Long::sum);
                for (int j = 0; j < k; j++) {
                    long share = base + (j < extra ? 1 : 0);
                    int winner = winners.get(j);
                    collected.merge(winner, share, Long::sum);
                    owed.computeIfAbsent(payer, p -> new HashMap<>()).merge(winner, share, Long::sum);
                }
            }
            for (int w : winners) weeksWon.merge(w, 1, Integer::sum);
            weeks.add(new WeekResult(e.getKey(), List.copyOf(winners), max, runnerUp, payers.size(),
                    payers.size() * stakeCents));
        }

        Map<Integer, Balance> balances = new LinkedHashMap<>();
        Map<Integer, Long> net = new HashMap<>();
        for (int id : ids) {
            long c = collected.getOrDefault(id, 0L);
            long p = paid.getOrDefault(id, 0L);
            balances.put(id, new Balance(id, weeksWon.getOrDefault(id, 0), c, p, c - p));
            net.put(id, c - p);
        }

        List<Debt> pairwise = pairwise(ids, owed);
        return new Ledger(List.copyOf(weeks), balances, pairwise, shortcut(pairwise, net));
    }

    private static List<Debt> pairwise(TreeSet<Integer> ids, Map<Integer, Map<Integer, Long>> owed) {
        List<Debt> out = new ArrayList<>();
        List<Integer> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                int a = list.get(i);
                int b = list.get(j);
                long d = owed.getOrDefault(a, Map.of()).getOrDefault(b, 0L)
                        - owed.getOrDefault(b, Map.of()).getOrDefault(a, 0L);
                if (d > 0) out.add(new Debt(a, b, d));
                else if (d < 0) out.add(new Debt(b, a, -d));
            }
        }
        out.sort(Comparator.comparingInt(Debt::fromRosterId).thenComparingInt(Debt::toRosterId));
        return List.copyOf(out);
    }

    /** Greedy per connected component of the pairwise debt graph (see the class javadoc for the proof). */
    private static List<Debt> shortcut(List<Debt> pairwise, Map<Integer, Long> net) {
        Map<Integer, Integer> parent = new HashMap<>();
        for (Debt d : pairwise) union(parent, d.fromRosterId(), d.toRosterId());
        Map<Integer, TreeSet<Integer>> components = new TreeMap<>();
        for (Integer id : new ArrayList<>(parent.keySet())) {
            components.computeIfAbsent(find(parent, id), r -> new TreeSet<>()).add(id);
        }
        List<Debt> out = new ArrayList<>();
        for (TreeSet<Integer> members : components.values()) {
            Map<Integer, Long> remaining = new HashMap<>();
            for (int m : members) remaining.put(m, net.getOrDefault(m, 0L));
            while (true) {
                Integer debtor = null;
                Integer creditor = null;
                for (int m : members) { // ascending, so the strict comparisons keep the lowest id on a tie
                    long v = remaining.get(m);
                    if (v < 0 && (debtor == null || v < remaining.get(debtor))) debtor = m;
                    if (v > 0 && (creditor == null || v > remaining.get(creditor))) creditor = m;
                }
                if (debtor == null || creditor == null) break;
                long amount = Math.min(-remaining.get(debtor), remaining.get(creditor));
                out.add(new Debt(debtor, creditor, amount));
                remaining.merge(debtor, amount, Long::sum);
                remaining.merge(creditor, -amount, Long::sum);
            }
        }
        out.sort(Comparator.comparingInt(Debt::fromRosterId).thenComparingInt(Debt::toRosterId));
        return List.copyOf(out);
    }

    private static int find(Map<Integer, Integer> parent, int x) {
        parent.putIfAbsent(x, x);
        int root = x;
        while (parent.get(root) != root) root = parent.get(root);
        parent.put(x, root);
        return root;
    }

    private static void union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent.put(Math.max(ra, rb), Math.min(ra, rb));
    }
}
