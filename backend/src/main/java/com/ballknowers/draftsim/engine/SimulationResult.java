package com.ballknowers.draftsim.engine;

import java.util.List;
import java.util.Map;

public record SimulationResult(
        int iterations,
        double temperature,
        int teams,
        int rounds,
        int mySlot,
        List<Integer> myPicks,
        List<PredictedPick> board,
        List<AvailabilityRow> availability,
        Map<Integer, List<Candidate>> bestAvailable,
        Confidence confidence
) {

    public record PlayerRef(long id, String name, String position, String team, double adp) {}

    /**
     * One cell of the predicted board.
     *
     * @param probability marginal — the share of runs in which this player went at
     *                    this pick. NOT the probability of the board as a whole.
     * @param isModal     false when this player is not the single most-voted at this
     *                    pick, which happens where the most-voted player was already
     *                    assigned earlier. Those cells are the least certain.
     */
    public record PredictedPick(
            int pickNo, int round, int slot, String manager,
            PlayerRef player, double probability, boolean isModal,
            List<Candidate> alternatives) {}

    public record Candidate(PlayerRef player, double probability) {}

    /**
     * Probability this player is still on the board when each of your picks
     * comes up. This is the output that changes decisions.
     */
    public record AvailabilityRow(PlayerRef player, Map<Integer, Double> survivalByPick) {}

    /**
     * How much the numbers above should be trusted. Carried in the payload on
     * purpose so the UI cannot quietly present a thin model as a confident one.
     */
    public record Confidence(
            int draftsObserved,
            int scoreablePicks,
            int managersWithHistory,
            /** Seats running on what the user typed, with no history behind it. */
            int managersStated,
            /** Seats with neither history nor a stated opinion: the league average. */
            int managersNeutral,
            int totalSeats,
            String boardSource,
            List<String> caveats) {}
}
