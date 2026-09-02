package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that keeps Sleeper's archive of retired players off the board.
 * See BoardService.dropOffRoster for why team, and not status/active, is the
 * signal being trusted here.
 */
class BoardServiceDraftableTest {

    private static Player player(String name, String team, String status) {
        return new Player(1L, Sport.NFL, "sleeper-" + name, name, List.of(Position.RB),
                team, status, null, 27, 7);
    }

    @Test
    void rosteredPlayerIsDraftable() {
        assertTrue(BoardService.draftable(player("Jahmyr Gibbs", "DET", "Active"), false));
    }

    @Test
    void offRosterPlayerIsNotDraftableEvenWhenSleeperSaysActive() {
        // The actual Sleeper record as of 2026-09-01: search_rank 27, status
        // "Active", active true, team null. He last played in 2021.
        assertFalse(BoardService.draftable(player("Todd Gurley", null, "Active"), false));
    }

    @Test
    void offRosterPlayerIsDraftableWhenTheMarketDraftsHimAnyway() {
        assertTrue(BoardService.draftable(player("Unsigned Free Agent", null, "Active"), true));
    }

    @Test
    void blankTeamCountsAsOffRoster() {
        assertFalse(BoardService.draftable(player("Blank Team", "  ", "Active"), false));
    }

    @Test
    void unknownPlayerSurvivesOnlyOnTheMarketSignal() {
        assertFalse(BoardService.draftable(null, false));
        assertTrue(BoardService.draftable(null, true));
    }
}
