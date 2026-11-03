package com.ballknowers.draftsim.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** claude/official-week-scores.md: season totals are captured for scored, regular-season legs only. */
class FptsSnapshotLegTest {

    @Test
    void regularSeasonLegsAreCaptured() {
        assertTrue(LeagueHistoryIngestService.fptsSnapshotLeg(1, 20));
        assertTrue(LeagueHistoryIngestService.fptsSnapshotLeg(19, 20));
    }

    @Test
    void unscoredPlayoffAndUnknownLegsAreNot() {
        assertFalse(LeagueHistoryIngestService.fptsSnapshotLeg(0, 20));
        assertFalse(LeagueHistoryIngestService.fptsSnapshotLeg(20, 20));
        assertFalse(LeagueHistoryIngestService.fptsSnapshotLeg(5, 0));
    }
}
