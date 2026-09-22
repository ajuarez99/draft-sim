package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link LeagueRepository.LeagueRow#complete()} to the rule
 * data-model.md states verbatim for "league.status": a null status means not
 * yet known and is treated as not complete, and must never be treated as
 * complete, because that is the failure mode 006-deeper-history-both-sports
 * exists to fix -- popsharky and gregmullen were both crowned champions of a
 * 2026 season, from ingest logic that had no way to tell "still playing" from
 * "finished" apart from chain position.
 *
 * <p>Plain unit test, not a Spring integration test: {@link LeagueRepository.LeagueRow}
 * is a record with no dependency on the database, so this constructs it
 * directly rather than paying for an application context.
 */
class LeagueStatusTest {

    private LeagueRepository.LeagueRow rowWithStatus(String status) {
        return new LeagueRepository.LeagueRow(1L, Sport.NFL, "sleeper-league", "Test League",
                2026, 12, List.of("QB", "BN"), 1.0, null, status);
    }

    @Test
    void nullStatusIsNotComplete() {
        // Rows ingested before V21 have no status at all until the next ingest
        // walk. That must read as "not yet known", not as "finished".
        assertFalse(rowWithStatus(null).complete());
    }

    @Test
    void preDraftIsNotComplete() {
        assertFalse(rowWithStatus("pre_draft").complete());
    }

    @Test
    void draftingIsNotComplete() {
        assertFalse(rowWithStatus("drafting").complete());
    }

    @Test
    void inSeasonIsNotComplete() {
        // This is the exact case that crowned popsharky and gregmullen
        // champions of a still-live 2026 season.
        assertFalse(rowWithStatus("in_season").complete());
    }

    @Test
    void onlyCompleteIsComplete() {
        assertTrue(rowWithStatus("complete").complete());
    }
}
