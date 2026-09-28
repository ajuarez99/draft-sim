package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The Jabari Smith Jr. Award (specs/008-season-superlatives US7, research R16):
 * the player picked up the most times off waivers or free agency, across the
 * covered weeks. Pure and static, deliberately -- no Postgres, so {@code
 * MostAddedPlayersTest} can exercise every case in-memory (T074).
 *
 * <p>Rule, verbatim from research R16 decision 1: count every completed
 * {@code WAIVER} or {@code FREE_AGENT} add (a team re-adding the same player
 * counts again), rank by TOTAL adds -- not distinct teams -- and name every
 * player tied at the top (FR-024, no invented tiebreaker).
 */
public final class MostAddedPlayers {

    private MostAddedPlayers() {}

    /**
     * The fewest adds a player needs before the award names him. <b>Hand-set,
     * arbitrary</b>: decided by Allan on 2026-09-28 after live verification found
     * NFL 2026, through week 2, tied 25 players at 1 add each (spec clarification
     * 18). One add is just a roster move; a player becomes a pickup magnet the
     * second time. Below this, the award says nobody has been picked up twice yet.
     * The counting itself ({@link #rank}) is unaffected -- this only decides
     * whether its top is worth naming.
     */
    public static final int MIN_ADDS_TO_NAME = 2;

    /** One tied-top player: his total adds, how many distinct rosters made them, and every counted add. */
    public record Ranked(String playerId, int adds, int distinctTeams, List<CompletedAdd> counted) {}

    /**
     * @param adds             every completed add for the league-season (any type -- this method
     *                         applies the WAIVER/FREE_AGENT filter itself, per research R16
     *                         decision 1: a TRADE or COMMISSIONER add of the same player counts
     *                         for nothing here)
     * @param throughWeek      the season window's own bound (research R16 decision 3): an add with
     *                         {@code week < 1} or {@code week > throughWeek} is never counted, and
     *                         this is a required argument, never defaulted
     * @param eligiblePlayerId research R16 decision 2: a player id this returns {@code false} for
     *                         (a football team defense) is never counted, even at the top
     */
    public static List<Ranked> rank(List<CompletedAdd> adds, int throughWeek, Predicate<String> eligiblePlayerId) {
        Map<String, List<CompletedAdd>> byPlayer = new LinkedHashMap<>();
        for (CompletedAdd a : adds) {
            if (!"WAIVER".equals(a.type()) && !"FREE_AGENT".equals(a.type())) continue;
            if (a.week() < 1 || a.week() > throughWeek) continue;
            if (!eligiblePlayerId.test(a.playerId())) continue;
            byPlayer.computeIfAbsent(a.playerId(), k -> new ArrayList<>()).add(a);
        }
        if (byPlayer.isEmpty()) return List.of();

        int max = byPlayer.values().stream().mapToInt(List::size).max().orElseThrow();
        // Deterministic tie order: by playerId, not insertion order.
        List<String> topIds = byPlayer.entrySet().stream()
                .filter(e -> e.getValue().size() == max)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        List<Ranked> out = new ArrayList<>();
        for (String playerId : topIds) {
            List<CompletedAdd> counted = new ArrayList<>(byPlayer.get(playerId));
            // Same ordering as WaiverPickupAttribution.isMoreRecent: week, then
            // createdAt, with a null createdAt sorting first.
            counted.sort(Comparator.<CompletedAdd>comparingInt(CompletedAdd::week)
                    .thenComparing(CompletedAdd::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            int distinctTeams = (int) counted.stream().map(CompletedAdd::rosterId).distinct().count();
            out.add(new Ranked(playerId, max, distinctTeams, counted));
        }
        return out;
    }
}
