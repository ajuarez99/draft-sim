package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.sport.SportRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replacement level for the stats leaderboard (specs/022-player-stat-analysis research R11, data-model
 * "Replacement"). A pure class: players, the league's slots and the sport's eligibility rule in, levels and
 * per-player value over replacement out.
 *
 * <p><b>The rule, a labelled simplification (FR-033).</b> The starting slots are {@code roster_positions}
 * minus {@code BN}, {@code IR} and {@code TAXI}, ordered single-position slots first, then {@code G} and
 * {@code F}, then {@code UTIL} (stable within a class, so the league's own order). For each slot in that
 * order, {@code teams} times, the best remaining qualified player eligible for the slot is placed. A
 * position's replacement level is the best player left unplaced who is eligible at that position, or null
 * when none is. A player's value over replacement is the largest {@code fpPerGame - level} over his
 * eligible positions, and the position that gave it is reported. It is not a claim about who is on waivers:
 * a rostered bench player counts as "not starting".
 *
 * <p>Eligibility is {@link SportRules#isEligible}, never restated here. Ties between equal values break the
 * way the leaderboard's ranks do (more games, then name, then id), so the fill is deterministic.
 */
public final class ReplacementLevel {

    private ReplacementLevel() {}

    public static final String RULE = "GREEDY_SLOT_FILL";

    /** The five NBA positions a replacement level is reported for, in display order. */
    public static final List<String> POSITIONS = List.of("PG", "SG", "SF", "PF", "C");

    private static final Set<String> NOT_STARTING = Set.of("BN", "IR", "TAXI");
    private static final Set<String> SINGLE = Set.copyOf(POSITIONS);

    /**
     * One qualified player. {@code fpPerGame} is the value the ranks use (rounded to two decimals);
     * {@code games} and the player's name and sleeper id only order ties.
     */
    public record Qualified(Player player, int games, double fpPerGame) {}

    /** The wire object: {@code byPosition} has an entry per NBA position, null where no one is left. */
    public record Replacement(Map<String, Double> byPosition, String rule, int teams, List<String> slots) {}

    /** A player's value over replacement, and the position it was taken at. Both null when no level applies. */
    public record Vor(Double value, String position) {
        static final Vor NONE = new Vor(null, null);
    }

    /** The levels and each qualified player's value over replacement, keyed by sleeper id. */
    public record Result(Replacement replacement, Map<String, Vor> byPlayer) {}

    /** The starting slots in fill order (see the class comment). */
    public static List<String> startingSlots(List<String> rosterPositions) {
        List<String> slots = new ArrayList<>();
        for (String s : rosterPositions) if (!NOT_STARTING.contains(s)) slots.add(s);
        // List.sort is stable, so the league's own order survives inside a class.
        slots.sort(Comparator.comparingInt(ReplacementLevel::slotClass));
        return slots;
    }

    private static int slotClass(String slot) {
        if (SINGLE.contains(slot)) return 0;
        if (slot.equals("G") || slot.equals("F")) return 1;
        if (slot.equals("UTIL")) return 2;
        return 3;
    }

    public static Result of(List<Qualified> qualified, List<String> rosterPositions, int teams, SportRules rules) {
        List<String> slots = startingSlots(rosterPositions);
        List<Qualified> pool = new ArrayList<>(qualified);
        pool.sort(Comparator.comparingDouble(Qualified::fpPerGame).reversed()
                .thenComparing(Comparator.comparingInt(Qualified::games).reversed())
                .thenComparing(q -> q.player().name(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(q -> q.player().sleeperId(), Comparator.nullsLast(Comparator.naturalOrder())));

        Set<String> placed = new HashSet<>();
        for (String slot : slots) {
            for (int copy = 0; copy < teams; copy++) {
                for (Qualified q : pool) {
                    if (placed.contains(q.player().sleeperId()) || !rules.isEligible(q.player(), slot)) continue;
                    placed.add(q.player().sleeperId());
                    break;
                }
            }
        }

        Map<String, Double> byPosition = new LinkedHashMap<>();
        for (String position : POSITIONS) {
            Double level = null;
            for (Qualified q : pool) {
                if (placed.contains(q.player().sleeperId()) || !rules.isEligible(q.player(), position)) continue;
                level = q.fpPerGame();
                break;                                       // pool is best first
            }
            byPosition.put(position, level);
        }

        Map<String, Vor> byPlayer = new HashMap<>();
        for (Qualified q : pool) byPlayer.put(q.player().sleeperId(), vor(q.player(), q.fpPerGame(), byPosition, rules));
        return new Result(new Replacement(byPosition, RULE, teams, List.copyOf(slots)), byPlayer);
    }

    /**
     * The largest {@code fpPerGame - level} over the player's eligible positions that have a level, at two
     * decimals; the first such position in {@link #POSITIONS} order wins a tie. {@link Vor#NONE} when no
     * eligible position has a level.
     */
    public static Vor vor(Player player, double fpPerGame, Map<String, Double> byPosition, SportRules rules) {
        String best = null;
        double bestValue = 0;
        for (String position : POSITIONS) {
            Double level = byPosition.get(position);
            if (level == null || !rules.isEligible(player, position)) continue;
            double v = fpPerGame - level;
            if (best == null || v > bestValue) {
                best = position;
                bestValue = v;
            }
        }
        return best == null ? Vor.NONE : new Vor(Math.round(bestValue * 100.0) / 100.0, best);
    }
}
