package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The naive board — most-voted player at each pick, computed independently — is
 * the correct marginal statistic and produces a round one where the same player
 * appears at seven slots. These pin the assignment that fixes the reading without
 * lying about the numbers.
 */
class BoardAssemblerTest {

    private static List<Map<Long, Integer>> counts(int picks) {
        List<Map<Long, Integer>> l = new ArrayList<>();
        for (int i = 0; i <= picks; i++) l.add(new HashMap<>());
        return l;
    }

    /** Player 1 is the most-voted at all seven picks, which is what really happens. */
    private static List<Map<Long, Integer>> theProblemCase() {
        var c = counts(7);
        for (int p = 1; p <= 7; p++) {
            c.get(p).put(1L, 700 - p * 10);
            c.get(p).put((long) (10 + p), 200);
            c.get(p).put((long) (20 + p), 100);
        }
        return c;
    }

    @Test
    void noPlayerIsAssignedTwice() {
        Set<Long> seen = new HashSet<>();
        for (var a : BoardAssembler.assemble(theProblemCase(), 1000, 3)) {
            assertTrue(seen.add(a.playerId()), "player " + a.playerId() + " assigned twice");
        }
    }

    @Test
    void theFirstPickStillGetsTheGenuinelyModalPlayer() {
        var board = BoardAssembler.assemble(theProblemCase(), 1000, 3);
        assertEquals(1L, board.getFirst().playerId());
        assertTrue(board.getFirst().isModal());
    }

    @Test
    void laterPicksFallThroughAndAreFlaggedNotModal() {
        var board = BoardAssembler.assemble(theProblemCase(), 1000, 3);
        assertFalse(board.get(1).isModal(), "pick 2's modal player was taken at pick 1");
        assertEquals(12L, board.get(1).playerId(), "should fall through to the runner-up");
    }

    /**
     * The displayed probability must stay marginal. Reporting the modal player's
     * share next to a different player would be the worst of both worlds.
     */
    @Test
    void probabilitiesAreTheAssignedPlayersOwnMarginal() {
        var board = BoardAssembler.assemble(theProblemCase(), 1000, 3);
        assertEquals(0.690, board.getFirst().probability(), 1e-9);
        assertEquals(0.200, board.get(1).probability(), 1e-9);
    }

    @Test
    void theDisplacedModalPlayerRemainsVisibleAsAnAlternative() {
        var board = BoardAssembler.assemble(theProblemCase(), 1000, 3);
        assertTrue(board.get(1).alternatives().stream().anyMatch(r -> r.playerId() == 1L));
        assertTrue(board.getFirst().alternatives().stream().noneMatch(r -> r.playerId() == 1L),
                "the chosen player is not its own alternative");
    }

    @Test
    void picksWithNoVotesAreSkippedRatherThanZeroFilled() {
        assertTrue(BoardAssembler.assemble(counts(5), 1000, 3).isEmpty());

        var sparse = counts(4);
        sparse.get(2).put(9L, 500);
        var board = BoardAssembler.assemble(sparse, 1000, 3);
        assertEquals(1, board.size());
        assertEquals(2, board.getFirst().pickNo());
    }

    @Test
    void anExhaustedPoolStillFillsEveryPick() {
        var c = counts(3);
        for (int p = 1; p <= 3; p++) c.get(p).put(5L, 1000);
        var board = BoardAssembler.assemble(c, 1000, 3);
        assertEquals(3, board.size(), "no holes in the board");
        assertEquals(5L, board.get(1).playerId());
    }

    @Test
    void tiesBreakDeterministically() {
        var c = counts(2);
        c.get(1).put(3L, 100); c.get(1).put(7L, 100);
        c.get(2).put(3L, 100); c.get(2).put(7L, 100);
        var a = BoardAssembler.assemble(c, 1000, 3);
        var b = BoardAssembler.assemble(c, 1000, 3);
        assertEquals(a.getFirst().playerId(), b.getFirst().playerId());
        assertEquals(3L, a.getFirst().playerId(), "lower id wins a tie");
        assertEquals(7L, a.get(1).playerId());
    }
}
