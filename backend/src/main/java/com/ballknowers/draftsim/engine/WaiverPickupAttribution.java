package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;

import java.time.Instant;
import java.util.*;

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

    private record ParsedTx(int week, Instant createdAt, String type, Map<String, Integer> adds) {}

    private record Used(int transactionWeek, String type) {}

    /**
     * @param transactions every stored transaction for the league-season (any status/type -- this
     *                     method applies research R8's {@code status = 'complete'} filter itself)
     * @param starterWeeks every (week, roster)'s stored starters and points, already bounded to
     *                     whatever window the caller wants (T039 bounds this to the season window)
     */
    public static Map<Integer, RosterTotal> attribute(List<LeagueTransactionRepository.Row> transactions,
                                                       List<StarterWeek> starterWeeks) {
        List<ParsedTx> parsed = new ArrayList<>();
        for (LeagueTransactionRepository.Row r : transactions) {
            if (!"complete".equals(r.status())) continue;
            Map<String, Object> addsRaw = (r.addsJson() == null || r.addsJson().isBlank())
                    ? Map.of() : JsonUtil.readMap(r.addsJson());
            Map<String, Integer> adds = new HashMap<>();
            addsRaw.forEach((playerId, rosterObj) -> {
                if (rosterObj instanceof Number n) adds.put(playerId, n.intValue());
            });
            if (adds.isEmpty()) continue;
            parsed.add(new ParsedTx(r.week(), r.createdAt(), r.type(), adds));
        }

        // (rosterId + ":" + playerId) -> startedWeek -> which transaction won that week's lookup.
        Map<String, TreeMap<Integer, Used>> usedByKey = new HashMap<>();

        for (StarterWeek sw : starterWeeks) {
            for (String playerId : sw.starterIds()) {
                ParsedTx best = null;
                for (ParsedTx tx : parsed) {
                    if (tx.week() > sw.week()) continue;
                    Integer toRoster = tx.adds().get(playerId);
                    if (toRoster == null || toRoster != sw.rosterId()) continue;
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
    private static boolean isMoreRecent(ParsedTx candidate, ParsedTx current) {
        if (candidate.week() != current.week()) return candidate.week() > current.week();
        Instant a = candidate.createdAt();
        Instant b = current.createdAt();
        if (a == null) return false;
        if (b == null) return true;
        return a.isAfter(b);
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
