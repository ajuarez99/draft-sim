package com.ballknowers.draftsim.engine;

import java.util.*;

/**
 * Turns per-pick vote counts into a board a human can read.
 *
 * The naive version — take the most-voted player at each pick independently — is
 * the correct *marginal* statistic and produces a board where the same player
 * appears at seven different slots in round one. That is not a bug in the
 * arithmetic; a marginal mode per cell carries no exclusivity constraint. But
 * nobody reads a "predicted board" that way, and a board showing Justin Jefferson
 * seven times reads as broken software rather than as a distribution.
 *
 * So assignment walks the picks in order and takes each pick's highest-voted
 * player that has not already been assigned. The reported probability stays the
 * marginal one — the share of runs in which that player went at that pick — which
 * is the honest number and is not the same as the probability of the whole board.
 *
 * Where the assigned player is not the marginal mode, {@code isModal} is false.
 * Those are the cells the model is least sure about, and the UI can say so.
 */
public final class BoardAssembler {

    private BoardAssembler() {}

    /** One assigned cell. probability is marginal, not joint. */
    public record Assignment(int pickNo, long playerId, double probability, boolean isModal,
                             List<Ranked> alternatives) {}

    public record Ranked(long playerId, double probability) {}

    /**
     * @param countsByPick index is pick number (0 unused), value is playerId -> votes
     * @param iterations   total runs, for turning votes into probabilities
     * @param alternatives how many runners-up to keep per pick
     */
    public static List<Assignment> assemble(List<Map<Long, Integer>> countsByPick,
                                            int iterations,
                                            int alternatives) {
        List<Assignment> out = new ArrayList<>();
        Set<Long> taken = new HashSet<>();

        for (int pickNo = 1; pickNo < countsByPick.size(); pickNo++) {
            Map<Long, Integer> counts = countsByPick.get(pickNo);
            if (counts == null || counts.isEmpty()) continue;

            List<Map.Entry<Long, Integer>> ranked = counts.entrySet().stream()
                    .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed()
                            // ties broken by id so the board is reproducible run to run
                            .thenComparing(Map.Entry::getKey))
                    .toList();

            Map.Entry<Long, Integer> modal = ranked.getFirst();

            Map.Entry<Long, Integer> chosen = null;
            for (Map.Entry<Long, Integer> e : ranked) {
                if (taken.add(e.getKey())) { chosen = e; break; }
            }
            // Every candidate at this pick is already on someone's roster. Rare, but
            // possible late in a draft where few distinct players ever appear. Fall
            // back to the modal player rather than leaving a hole in the board.
            if (chosen == null) chosen = modal;
            // chosen is reassigned above, so it is not effectively final and cannot
            // be captured by the lambda below.
            final long chosenId = chosen.getKey();

            List<Ranked> alts = ranked.stream()
                    .filter(e -> e.getKey() != chosenId)
                    .limit(alternatives)
                    .map(e -> new Ranked(e.getKey(), e.getValue() / (double) iterations))
                    .toList();

            out.add(new Assignment(
                    pickNo,
                    chosenId,
                    chosen.getValue() / (double) iterations,
                    chosenId == modal.getKey(),
                    alts));
        }
        return out;
    }
}
