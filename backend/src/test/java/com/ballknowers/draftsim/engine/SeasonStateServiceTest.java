package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonStateService.SeasonState;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * specs/030-post-draft-season-view T012: every branch of the season-state rule, in its check order
 * (data-model E2 as amended by plan review B2/N6). Collaborators are mocked; the rule is the subject.
 */
class SeasonStateServiceTest {

    private static final long LEAGUE_ID = 77L;

    private DraftRepository drafts;
    private ScoredWeeks scored;
    private SeasonStateService svc;

    @BeforeEach
    void setUp() {
        drafts = mock(DraftRepository.class);
        scored = mock(ScoredWeeks.class);
        svc = new SeasonStateService(drafts, scored);
        when(drafts.forLeague(LEAGUE_ID)).thenReturn(Optional.empty());
        when(scored.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(0, 0, Set.of()));
    }

    private static LeagueRow league(String status) {
        return new LeagueRow(LEAGUE_ID, Sport.NBA, "L1", "Ball Knowers", 2026, 12, List.of("PG"), 0.0, null, status);
    }

    private void draft(String status) {
        when(drafts.forLeague(LEAGUE_ID)).thenReturn(Optional.of(
                new DraftRepository.DraftRow(1L, LEAGUE_ID, "D1", 2026, 15, 12, status, Map.of())));
    }

    private void weeks(int stored, int latestFinal) {
        Set<Integer> finals = new java.util.TreeSet<>();
        for (int w = 1; w <= latestFinal; w++) finals.add(w);
        when(scored.of(LEAGUE_ID)).thenReturn(new ScoredWeeks.Snapshot(stored, latestFinal, finals));
    }

    @Test
    void aCompleteLeagueIsCompleteEvenWithNoStoredWeeks() {
        draft("complete");
        assertEquals(SeasonState.COMPLETE, svc.stateOf(league("complete")));
    }

    @Test
    void aDraftThatIsStillDraftingIsPreDraftEvenIfStrayWeeksAreStored() {
        draft("drafting");
        weeks(2, 2);
        assertEquals(SeasonState.PRE_DRAFT, svc.stateOf(league("in_season")));
    }

    @Test
    void aCompleteDraftWithNoWeeksIsDraftedUnscored() {
        draft("complete");
        assertEquals(SeasonState.DRAFTED_UNSCORED, svc.stateOf(league("in_season")));
    }

    @Test
    void aCompleteDraftWithAFinalWeekIsInSeason() {
        draft("complete");
        weeks(1, 1);
        assertEquals(SeasonState.IN_SEASON, svc.stateOf(league("in_season")));
    }

    /** Plan review B2: NBA week 1 is stored partial on its first night; that must not flip the home. */
    @Test
    void aStoredButNotFinalWeekOneIsStillDraftedUnscored() {
        draft("complete");
        weeks(1, 0);
        assertEquals(SeasonState.DRAFTED_UNSCORED, svc.stateOf(league("in_season")));
    }

    @Test
    void noDraftRowAndNoWeeksFallsBackToLeagueStatusAndCountsAsDrafted() {
        assertEquals(SeasonState.DRAFTED_UNSCORED, svc.stateOf(league("in_season")));
    }

    @Test
    void noDraftRowFallsBackToLeagueStatusForPreDraft() {
        assertEquals(SeasonState.PRE_DRAFT, svc.stateOf(league("pre_draft")));
        assertEquals(SeasonState.PRE_DRAFT, svc.stateOf(league("drafting")));
    }

    @Test
    void noDraftRowWithAFinalWeekIsInSeason() {
        weeks(3, 3);
        assertEquals(SeasonState.IN_SEASON, svc.stateOf(league("in_season")));
    }

    @Test
    void aNullLeagueStatusIsNeverComplete() {
        draft("complete");
        assertEquals(SeasonState.DRAFTED_UNSCORED, svc.stateOf(league(null)));
    }
}
