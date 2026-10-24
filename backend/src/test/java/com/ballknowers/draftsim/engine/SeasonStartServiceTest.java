package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.DraftFixtures;
import com.ballknowers.draftsim.config.PreseasonProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.RosterProjection;
import com.ballknowers.draftsim.engine.SeasonStateService.SeasonState;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** specs/030 fix pass: failure isolation (R2), first week (R7), team labels (N4), with collaborators mocked. */
class SeasonStartServiceTest {

    private LeagueRepository leagues;
    private SeasonStateService state;
    private ScheduleGridService schedule;
    private LeagueAnalysisService analysis;
    private DraftRepository drafts;
    private DraftGradesService grades;
    private ScoredWeeks scoredWeeks;
    private SeasonStartService service;
    private LeagueRepository.LeagueRow league;

    @BeforeEach
    void setUp() {
        leagues = mock(LeagueRepository.class);
        state = mock(SeasonStateService.class);
        schedule = mock(ScheduleGridService.class);
        analysis = mock(LeagueAnalysisService.class);
        drafts = mock(DraftRepository.class);
        grades = mock(DraftGradesService.class);
        scoredWeeks = mock(ScoredWeeks.class);
        SleeperClient sleeper = mock(SleeperClient.class);
        service = new SeasonStartService(leagues, state, schedule, sleeper, analysis, drafts, grades, scoredWeeks,
                new PreseasonProperties(3.0, 3));
        league = new LeagueRepository.LeagueRow(7, Sport.NBA, "L7", "Ball Knowers", 2026, 12,
                List.of("PG"), 0.0, null, "in_season");
        when(leagues.bySleeperId("L7")).thenReturn(Optional.of(league));
        when(schedule.forLeague(anyString())).thenReturn(Optional.empty());
        when(drafts.forLeague(anyLong())).thenReturn(Optional.empty());
        when(scoredWeeks.of(anyLong())).thenReturn(new ScoredWeeks.Snapshot(0, 0, Set.of()));
    }

    private static RosterProjection roster(int id, double total, boolean me) {
        return new RosterProjection(id, (long) id, "u" + id, null, id, me, total, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), 0, 0);
    }

    @Test
    void aFailedStrengthPassStillAnswersTheStateAndTheFirstWeek() {
        when(state.stateOf(league)).thenReturn(SeasonState.DRAFTED_UNSCORED);
        when(analysis.projectionsRead(any(), any())).thenThrow(new IllegalStateException("sleeper 503"));

        var r = service.read("L7", "u1").orElseThrow();

        assertEquals("DRAFTED_UNSCORED", r.state());
        assertFalse(r.strength().available());
        assertEquals("STRENGTH_FAILED", r.strength().reasonCode());
        assertNotNull(r.strength().reason());
        assertNotNull(r.firstWeek());
        assertNull(r.firstWeek().startersWithoutGame());
    }

    @Test
    void aFailedGradesReadLeavesGradesNullAndKeepsTheState() {
        when(state.stateOf(league)).thenReturn(SeasonState.IN_SEASON);
        when(drafts.forLeague(anyLong())).thenReturn(Optional.of(DraftFixtures.draftRow(
                1, 7, "D1", 2026, 13, 12, "complete", Map.of(), 0, null)));
        when(grades.read(any())).thenThrow(new IllegalStateException("db down"));

        var r = service.read("L7", "u1").orElseThrow();

        assertEquals("IN_SEASON", r.state());
        assertNull(r.draft().grades());
        assertEquals("D1", r.draft().sleeperDraftId());
    }

    @Test
    void firstWeekIsTheWeekAfterTheLatestFinalOneNotSleepersCurrentLeg() {
        when(state.stateOf(league)).thenReturn(SeasonState.DRAFTED_UNSCORED);
        when(leagues.currentLeg(anyLong())).thenReturn(java.util.OptionalInt.of(2));
        when(analysis.projectionsRead(any(), any())).thenThrow(new IllegalStateException("x"));

        var r = service.read("L7", "u1").orElseThrow();

        assertEquals(1, r.firstWeek().week());
    }

    @Test
    void theStrengthCarriesEveryRostersLabelAndTheFavoriteUsesIt() {
        when(state.stateOf(league)).thenReturn(SeasonState.DRAFTED_UNSCORED);
        List<RosterProjection> ranked = List.of(roster(1, 300, false), roster(2, 200, true), roster(3, 100, false),
                roster(4, 90, false), roster(5, 80, false));
        var p = new LeagueAnalysisService.Projections(true, null, null, List.of(), ranked, "PER_WEEK",
                "ONE_AVERAGE_GAME_PER_STARTER", null, List.of(), 3, null);
        when(analysis.projectionsRead(any(), any())).thenReturn(new LeagueAnalysisService.ProjectionsRead(
                p, new LeagueAnalysisService.Window(1, 1, 1, 0)));
        when(analysis.teamLabels(league)).thenReturn(List.of(
                new LeagueAnalysisService.TeamLabel(1, "Alpha", "u1"),
                new LeagueAnalysisService.TeamLabel(5, "Roster 5", null)));

        var s = service.read("L7", "u2").orElseThrow().strength();

        assertTrue(s.available());
        assertEquals(2, s.teamLabels().size());
        assertEquals("Alpha", s.favorite().leaders().getFirst().teamName());
        assertEquals(3, s.top().size());
        assertNull(s.me(), "me is rank 2, inside the top three");
        assertTrue(s.top().stream().allMatch(t -> t.byWeek().isEmpty() && t.starters().isEmpty()));
    }
}
