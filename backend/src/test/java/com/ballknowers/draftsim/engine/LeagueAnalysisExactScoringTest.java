package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PreseasonProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerProjectionRepository;
import com.ballknowers.draftsim.store.PlayerProjectionRepository.ScoringKey;
import com.ballknowers.draftsim.store.PlayerProjectionRepository.WeekLine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/036 T030: football's points path, flag off vs flag on. The flag stays off in the shipped config
 * (draftsim.projections.nfl-exact-scoring: false); this proves the switch does what it says either way.
 */
class LeagueAnalysisExactScoringTest {

    private static final LeagueRepository.LeagueRow LEAGUE =
            new LeagueRepository.LeagueRow(4L, Sport.NFL, "L4", "Foot", 2026, 10, List.of(), 1.0, null, "in_season");

    /** This league pays 0.5 per reception and 0.1 per receiving yard; Sleeper's pts_ppr column assumes 1.0. */
    private static final Map<String, Double> SCORING = Map.of("rec", 0.5, "rec_yd", 0.1);

    private static LeagueAnalysisService service(PlayerProjectionRepository projections, boolean exact) {
        var leagues = Mockito.mock(LeagueRepository.class);
        Mockito.when(leagues.scoringOf(4L)).thenReturn(SCORING);
        return new LeagueAnalysisService(leagues, null, null, projections, null, null, null, null, null, null,
                null, null, new GameScoringService(), new PreseasonProperties(3.0, 3), null, exact);
    }

    private static PlayerProjectionRepository repo() {
        var repo = Mockito.mock(PlayerProjectionRepository.class);
        // pts_ppr 11.0 is what the column pick reads; the stat line scored under THIS league is 0.5*5 + 0.1*60 = 8.5.
        Mockito.when(repo.totalsByPlayer("nfl", 2026, 7, 8, ScoringKey.PPR)).thenReturn(Map.of("a", 11.0, "old", 4.0));
        Mockito.when(repo.linesInWindow("nfl", 2026, 7, 8)).thenReturn(List.of(
                new WeekLine(7, "a", 11.0, null, null, Map.of("rec", 5.0, "rec_yd", 60.0)),
                new WeekLine(8, "a", 11.0, null, null, Map.of("rec", 5.0, "rec_yd", 60.0)),
                new WeekLine(8, "old", 4.0, null, null, null)));        // a row written before V36: no line
        return repo;
    }

    @Test
    void withTheFlagOffTheColumnPickIsUsedAndTheLinesAreNeverRead() {
        var repo = repo();
        var totals = service(repo, false).windowTotals(LEAGUE, ScoringKey.PPR, 7, 8);
        assertEquals(11.0, totals.get("a"), 1e-9);
        Mockito.verify(repo, Mockito.never()).linesInWindow(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void withTheFlagOnARowWithALineIsValuedByTheLeaguesOwnScoringAndARowWithoutFallsBack() {
        var totals = service(repo(), true).windowTotals(LEAGUE, ScoringKey.PPR, 7, 8);
        assertEquals(17.0, totals.get("a"), 1e-9, "two weeks of 8.5, not two weeks of the PPR column's 11.0");
        assertEquals(4.0, totals.get("old"), 1e-9, "no line: the column pick, row by row");
    }

    @Test
    void thePerWeekViewFollowsTheSameBranch() {
        var on = service(repo(), true).windowPerWeek(LEAGUE, ScoringKey.PPR, 7, 8);
        assertEquals(8.5, on.get(7).get("a"), 1e-9);
        assertEquals(4.0, on.get(8).get("old"), 1e-9);
    }
}
