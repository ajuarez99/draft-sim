package com.ballknowers.draftsim.engine;

import java.util.List;
import java.util.Map;

/**
 * @param draftSleeperId  the Sleeper draft to simulate. Its league supplies
 *                        settings; its draft_order supplies the seats.
 * @param mySlot          1-indexed draft slot whose picks the availability
 *                        curves are computed for.
 * @param iterations      Monte Carlo runs.
 * @param temperature     null = use the configured default. ~0 modal, 1 realistic, >2 chaos.
 * @param startState      already-made picks as pickNo -> sleeper player id.
 *                        Empty = cold start from 1.01. For a draft Sleeper has
 *                        already recorded picks for, leave null and they are
 *                        read from the draft itself.
 */
public record SimulationRequest(
        String draftSleeperId,
        int mySlot,
        int iterations,
        Double temperature,
        Map<Integer, String> startState,
        List<String> excludePlayerIds
) {
    public SimulationRequest {
        if (iterations <= 0) iterations = 1000;
        if (iterations > 20000) iterations = 20000;
    }
}
