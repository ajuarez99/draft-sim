package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The web client mirrors the snapshot depth; if this changes, the mirror must change with it. */
class MonteCarloRunnerSnapshotDepthTest {

    @Test
    void snapshotDepthIsPinnedAt75() {
        assertEquals(75, MonteCarloRunner.SNAPSHOT_DEPTH,
                "MonteCarloRunner.SNAPSHOT_DEPTH changed: update SNAPSHOT_DEPTH_MIRROR in "
                        + "web/src/insightConstants.ts to match, then update this pin");
    }

    @Test
    void alternativesArePinnedAt3() {
        assertEquals(3, MonteCarloRunner.ALTERNATIVES,
                "MonteCarloRunner.ALTERNATIVES changed: update CELL_CANDIDATES (ALTERNATIVES + 1) in "
                        + "web/src/pickInsight.ts to match, then update this pin");
    }
}
