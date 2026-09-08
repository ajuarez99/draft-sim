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
 *                        Null or empty both mean "not supplied" -- either way
 *                        {@code SimulationService.resolveStartState} falls
 *                        back to replaying every pick Sleeper has recorded for
 *                        the draft, so an empty map is NOT a cold start from
 *                        1.01. A cold start is not currently expressible
 *                        through this API.
 * @param seed            null = use a fresh, non-reproducible seed (today's
 *                        behaviour, and the normal case). Set this only to
 *                        diff a refactor against a captured baseline: the
 *                        same seed with everything else unchanged reproduces
 *                        the same run bit-for-bit.
 */
public record SimulationRequest(
        String draftSleeperId,
        int mySlot,
        int iterations,
        Double temperature,
        Map<Integer, String> startState,
        List<String> excludePlayerIds,
        Long seed
) {
    public SimulationRequest {
        if (iterations <= 0) iterations = 1000;
        if (iterations > 20000) iterations = 20000;
    }
}
