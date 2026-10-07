package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;

import java.time.Instant;
import java.util.*;

import static com.ballknowers.draftsim.util.Rounding.round2;

/**
 * Waiver Wire Warrior attribution (specs/008-season-superlatives US3, research
 * R8): starting-lineup points from players whose most recent arrival on that
 * roster was a completed {@code WAIVER} or {@code FREE_AGENT} add.
 *
 * <p>Pure and static, deliberately -- no Postgres, so {@code
 * WaiverPickupAttributionTest} can exercise every case in-memory (T037/T038).
 * The rule, verbatim from research R8: for each (week {@code w}, roster
 * {@code T}, starter {@code p}), take the most recent {@code
 * league_transaction} by {@code (week, created_at)} with {@code week <= w}
 * and {@code status = 'complete'} whose {@code adds} maps {@code p} to
 * {@code T}. Count {@code p}'s points for {@code T} that week only if that
 * transaction's type is {@code WAIVER} or {@code FREE_AGENT}.
 *
 * <p>This single "most recent arrival" lookup, run once per (week, roster,
 * starter), is what makes every case in the spec fall out for free: a
 * drafted player has no matching add at all; a player later traded or
 * commissioner-moved has a more recent, disqualifying transaction; a
 * drop-and-re-add is just two different weeks each finding their own most
 * recent transaction, so nothing is ever double-counted within one week.
 */
public final class WaiverPickupAttribution {

    private WaiverPickupAttribution() {}

    /** One roster's stored starters and per-player points for one week, already parsed out of the JSON columns. */
    public record StarterWeek(int week, int rosterId, Set<String> starterIds, Map<String, Double> playersPoints) {}

    /** One player's counted contribution to a roster's total, across however many started weeks counted. */
    public record PlayerContribution(String playerId, int addedWeek, String addType,
                                     List<Integer> startedWeeks, double points) {}

    /** One roster's total pickup points, and the players who made it up. */
    public record RosterTotal(int rosterId, double totalPoints, List<PlayerContribution> contributions) {}

    /**
     * One completed add, out of a {@code league_transaction} row's {@code adds} map
     * (specs/008-season-superlatives US7, T073). A row can in principle carry more than
     * one add -- this method doesn't assume otherwise -- so it's one {@code CompletedAdd}
     * per (row, added player) pair, not one per row. {@code rosterId} is the roster that
     * gained him, read from the {@code adds} map's value, never {@code Row.rosterId()}
     * (research R8/R16). {@code faabBid} passes {@link LeagueTransactionRepository.Row#faabBid()}
     * through unmodified: {@code null} means "not a bid", never {@code 0} (V19's comment).
     */
    public record CompletedAdd(int week, Instant createdAt, String type, String playerId,
                               int rosterId, Integer faabBid) {}

    private record Used(int transactionWeek, String type) {}

    /**
     * Every completed ({@code status = 'complete'}) add across these rows, one entry per
     * (row, added player) pair. A row whose {@code adds} is empty or blank contributes
     * nothing, and a non-numeric roster value for a given player is skipped (T073). Shared
     * by {@link #attribute} (which additionally needs TRADE/COMMISSIONER adds, to let a
     * later one disqualify an earlier pickup) and {@link MostAddedPlayers}, which filters
     * to WAIVER/FREE_AGENT itself -- this is the one place a completed pickup is parsed
     * out of the stored JSON (research R16 decision 4).
     */
    public static List<CompletedAdd> completedAdds(List<LeagueTransactionRepository.Row> rows) {
        List<CompletedAdd> out = new ArrayList<>();
        for (LeagueTransactionRepository.Row r : rows) {
            if (!"complete".equals(r.status())) continue;
            Map<String, Object> addsRaw = (r.addsJson() == null || r.addsJson().isBlank())
                    ? Map.of() : JsonUtil.readMap(r.addsJson());
            if (addsRaw.isEmpty()) continue;
            addsRaw.forEach((playerId, rosterObj) -> {
                if (rosterObj instanceof Number n) {
                    out.add(new CompletedAdd(r.week(), r.createdAt(), r.type(), playerId, n.intValue(), r.faabBid()));
                }
            });
        }
        return out;
    }

    /**
     * @param transactions every stored transaction for the league-season (any status/type -- this
     *                     method applies research R8's {@code status = 'complete'} filter itself,
     *                     via {@link #completedAdds})
     * @param starterWeeks every (week, roster)'s stored starters and points, already bounded to
     *                     whatever window the caller wants (T039 bounds this to the season window)
     */
    public static Map<Integer, RosterTotal> attribute(List<LeagueTransactionRepository.Row> transactions,
                                                       List<StarterWeek> starterWeeks) {
        List<CompletedAdd> parsed = completedAdds(transactions);

        // (rosterId + ":" + playerId) -> startedWeek -> which transaction won that week's lookup.
        Map<String, TreeMap<Integer, Used>> usedByKey = new HashMap<>();

        for (StarterWeek sw : starterWeeks) {
            for (String playerId : sw.starterIds()) {
                CompletedAdd best = null;
                for (CompletedAdd tx : parsed) {
                    if (tx.week() > sw.week()) continue;
                    if (!tx.playerId().equals(playerId) || tx.rosterId() != sw.rosterId()) continue;
                    if (best == null || isMoreRecent(tx, best)) best = tx;
                }
                if (best == null) continue; // drafted, or never stored as added to this roster
                if (!"WAIVER".equals(best.type()) && !"FREE_AGENT".equals(best.type())) continue;

                String key = sw.rosterId() + ":" + playerId;
                usedByKey.computeIfAbsent(key, k -> new TreeMap<>())
                        .put(sw.week(), new Used(best.week(), best.type()));
            }
        }

        Map<Integer, List<PlayerContribution>> contributionsByRoster = new HashMap<>();
        for (Map.Entry<String, TreeMap<Integer, Used>> e : usedByKey.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            int rosterId = Integer.parseInt(parts[0]);
            String playerId = parts[1];
            TreeMap<Integer, Used> startedWeeks = e.getValue();

            double points = 0;
            List<Integer> weeks = new ArrayList<>();
            for (StarterWeek sw : starterWeeks) {
                if (sw.rosterId() != rosterId || !startedWeeks.containsKey(sw.week())) continue;
                weeks.add(sw.week());
                Double p = sw.playersPoints().get(playerId);
                if (p != null) points += p;
            }
            Collections.sort(weeks);

            Map.Entry<Integer, Used> earliest = startedWeeks.firstEntry();
            PlayerContribution pc = new PlayerContribution(playerId, earliest.getValue().transactionWeek(),
                    earliest.getValue().type(), weeks, round2(points));
            contributionsByRoster.computeIfAbsent(rosterId, k -> new ArrayList<>()).add(pc);
        }

        Map<Integer, RosterTotal> out = new HashMap<>();
        for (Map.Entry<Integer, List<PlayerContribution>> e : contributionsByRoster.entrySet()) {
            List<PlayerContribution> contributions = new ArrayList<>(e.getValue());
            contributions.sort(Comparator.comparingDouble(PlayerContribution::points).reversed());
            double total = contributions.stream().mapToDouble(PlayerContribution::points).sum();
            out.put(e.getKey(), new RosterTotal(e.getKey(), round2(total), contributions));
        }
        return out;
    }

    /** {@code candidate} beats {@code current} when it's later by (week, createdAt); a null createdAt sorts first. */
    private static boolean isMoreRecent(CompletedAdd candidate, CompletedAdd current) {
        if (candidate.week() != current.week()) return candidate.week() > current.week();
        Instant a = candidate.createdAt();
        Instant b = current.createdAt();
        if (a == null) return false;
        if (b == null) return true;
        return a.isAfter(b);
    }
}
