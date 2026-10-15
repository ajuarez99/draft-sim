package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerProjectionRepository;
import com.ballknowers.draftsim.store.PlayerProjectionRepository.WeekLine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/036 T027: the parity tolerance rule from plan amendment S6, fixed before any result was looked at:
 * sum(|weight_k| * 0.005) over the scored keys the row carries, + 0.005.
 */
class ProjectionParityTest {

    private static final Map<String, Double> SCORING = Map.of("rec", 1.0, "rec_yd", 0.1, "pass_int", -1.0);

    @Test
    void toleranceIsHalfACentPerCarriedScoredKeyTimesItsWeightPlusHalfACent() {
        // carries rec (1.0) and rec_yd (0.1); pass_int is scored but absent from the row, pts_ppr is not scored.
        double t = ProjectionParityService.tolerance(SCORING, Map.of("rec", 5.0, "rec_yd", 61.0, "pts_ppr", 11.1));
        assertEquals(0.005 + 1.0 * 0.005 + 0.1 * 0.005, t, 1e-12);
    }

    @Test
    void aNegativeWeightCountsByItsMagnitude() {
        double t = ProjectionParityService.tolerance(SCORING, Map.of("pass_int", 0.7));
        assertEquals(0.005 + 0.005, t, 1e-12);
    }

    @Test
    void aRowCarryingNoScoredKeyStillGetsTheTotalsOwnRounding() {
        assertEquals(0.005, ProjectionParityService.tolerance(SCORING, Map.of("pts_ppr", 3.0)), 1e-12);
    }

    private static ProjectionParityService service(List<WeekLine> lines) {
        var repo = Mockito.mock(PlayerProjectionRepository.class);
        Mockito.when(repo.linesInWindow("nfl", 2026, 7, 7)).thenReturn(lines);
        var leagues = Mockito.mock(LeagueRepository.class);
        var row = new LeagueRepository.LeagueRow(9L, Sport.NFL, "L9", "Test", 2026, 10, List.of(), 1.0, null, "in_season");
        Mockito.when(leagues.all()).thenReturn(List.of(row));
        Mockito.when(leagues.scoringOf(9L)).thenReturn(SCORING);
        Mockito.when(leagues.bySleeperId("L9")).thenReturn(Optional.of(row));
        return new ProjectionParityService(repo, leagues, new GameScoringService());
    }

    @Test
    void aRowOffByLessThanItsToleranceIsWithinAndOneOffByMoreIsBeyond() {
        // rec 5 + rec_yd 61 => scored 5 + 6.1 = 11.10. Tolerance = .005 + .005 + .0005 = .0105.
        var ok = new WeekLine(7, "ok", 11.11, null, null, Map.of("rec", 5.0, "rec_yd", 61.0));      // diff -0.01
        var bad = new WeekLine(7, "bad", 11.50, null, null, Map.of("rec", 5.0, "rec_yd", 61.0));    // diff -0.40
        var noStats = new WeekLine(7, "old", 4.0, null, null, null);

        var r = service(List.of(ok, bad, noStats)).check("nfl", 2026, 7, null);

        assertEquals(2, r.rowsWithStats());
        assertEquals(1, r.rowsWithoutStats());
        assertEquals(1, r.withinTolerance());
        assertEquals(1, r.beyondTolerance());
        assertEquals("bad", r.firstBeyondTolerance().get(0).sleeperPlayerId());
        assertEquals(-0.40, r.firstBeyondTolerance().get(0).diff(), 1e-9);
        assertEquals(0.40, r.maxAbsDiff(), 1e-9);
        assertTrue(r.scoringSource().contains("L9"), "the result says which scoring it used");
    }

    @Test
    void theBeyondToleranceListIsCappedAtFiftyAndLargestFirst() {
        var lines = new java.util.ArrayList<WeekLine>();
        for (int i = 0; i < 60; i++) lines.add(new WeekLine(7, "p" + i, 20.0 + i, null, null, Map.of("rec", 5.0)));
        var r = service(lines).check("nfl", 2026, 7, "L9");
        assertEquals(60, r.beyondTolerance());
        assertEquals(50, r.firstBeyondTolerance().size());
        assertEquals("p59", r.firstBeyondTolerance().get(0).sleeperPlayerId());
    }

    @Test
    void basketballIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service(List.of()).check("nba", 2026, 7, null));
    }
}
