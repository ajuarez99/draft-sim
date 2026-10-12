package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.engine.SeasonStateService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.SeasonProjectionIngestService;
import com.ballknowers.draftsim.refresh.DailyRefreshService.DailyResult;
import com.ballknowers.draftsim.refresh.DailyRefreshService.Outcome;
import com.ballknowers.draftsim.refresh.DailyRefreshService.StepResult;
import com.ballknowers.draftsim.store.DailyCaptureRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** specs/009-auto-data-refresh T036: the daily job's outcomes, with every collaborator mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyRefreshServiceTest {

    @Mock private PlayerIngestService players;
    @Mock private BoardRefresh boardRefresh;
    @Mock private DailyCaptureRepository captures;
    @Mock private TrendingRefresh trending;
    @Mock private SeasonProjectionIngestService seasonProjections;
    @Mock private LeagueRepository leagues;
    @Mock private SeasonStateService seasonState;

    private DailyRefreshService service;

    private static FfcAdpService.Result ffcOk() {
        return new FfcAdpService.Result(true, 10, 9, 1, 5, false, null, List.of());
    }

    private static FfcAdpService.Result ffcFetchFailed() {
        return new FfcAdpService.Result(true, 0, 0, 0, 0, true, "fetch failed: 503", List.of());
    }

    /** What FfcAdpService returns for a non-football sport. */
    private static FfcAdpService.Result ffcSkippedForSport() {
        return new FfcAdpService.Result(false, 0, 0, 0, 0, false, "football-only; skipped", List.of());
    }

    private static BoardRefresh.Result board(FfcAdpService.Result adp) {
        return new BoardRefresh.Result(adp, new BoardService.Result(100, 60, 40, 0, 0), 12);
    }

    @BeforeEach
    void setUp() {
        service = new DailyRefreshService(players, boardRefresh, captures, trending, seasonProjections, leagues, seasonState);
        when(trending.refreshIfStale(any(), any())).thenReturn(TrendingRefresh.Outcome.DONE);
        when(players.ingest(any())).thenReturn(new PlayerIngestService.Result(2091, 300, true, 0));
        when(boardRefresh.run(any())).thenReturn(board(ffcOk()));
        when(leagues.all()).thenReturn(List.of(nbaLeague(2026), nbaLeague(2025)));
        when(seasonState.stateOf(any())).thenReturn(SeasonStateService.SeasonState.DRAFTED_UNSCORED);
        when(seasonProjections.refresh(any(), anyInt(), anyBoolean())).thenReturn(
                new SeasonProjectionIngestService.Result(true, false, 529, 3, 1591, "rotowire", null, null));
    }

    private static LeagueRepository.LeagueRow nbaLeague(int season) {
        return new LeagueRepository.LeagueRow(season, Sport.NBA, "L" + season, "Ball Knowers", season, 12,
                List.of("PG"), 0.0, null, "in_season");
    }

    private static StepResult step(DailyResult r, Sport sport, String kind) {
        return r.steps().stream().filter(s -> s.sport() == sport && s.kind().equals(kind)).findFirst().orElseThrow();
    }

    @Test
    void playersRunsAndRecordsARowForEverySport() {
        DailyResult r = service.runAll();

        for (Sport sport : Sport.values()) {
            StepResult p = step(r, sport, "PLAYERS");
            assertEquals(Outcome.DONE, p.outcome());
            assertEquals("playersWritten=2091 suspendedCount=0", p.detail());
            verify(captures).record(eq(sport), any(LocalDate.class), eq("PLAYERS"), any(), eq(p.detail()));
        }
        assertFalse(r.failed());
        assertEquals(LocalDate.now(ZoneOffset.UTC), r.date());
    }

    @Test
    void aSecondCallTheSameUtcDayIsSkippedAndDoesNotTouchPlayerIngest() {
        when(captures.exists(any(), any(), eq("PLAYERS"))).thenReturn(true);

        DailyResult r = service.runAll();

        for (Sport sport : Sport.values()) {
            StepResult p = step(r, sport, "PLAYERS");
            assertEquals(Outcome.SKIPPED_ALREADY_TODAY, p.outcome());
            assertNull(p.detail());
        }
        verifyNoInteractions(players);
        verify(captures, never()).record(any(), any(), eq("PLAYERS"), any(), any());
        assertFalse(r.failed(), "skipped is not failed");
    }

    @Test
    void aPlayersExceptionIsFailedForThatStepWhileBoardStillRuns() {
        when(players.ingest(Sport.NFL)).thenThrow(new IllegalStateException("sleeper 503"));

        DailyResult r = service.runAll();

        StepResult p = step(r, Sport.NFL, "PLAYERS");
        assertEquals(Outcome.FAILED, p.outcome());
        assertTrue(p.detail().contains("sleeper 503"));
        assertEquals(Outcome.DONE, step(r, Sport.NFL, "BOARD").outcome());
        verify(boardRefresh).run(Sport.NFL);
        verify(captures, never()).record(eq(Sport.NFL), any(), eq("PLAYERS"), any(), any());
        assertTrue(r.failed());
    }

    @Test
    void aBoardExceptionIsFailedAndTheNextSportStillRuns() {
        when(boardRefresh.run(Sport.NFL)).thenThrow(new IllegalStateException("no blended board"));

        DailyResult r = service.runAll();

        assertEquals(Outcome.FAILED, step(r, Sport.NFL, "BOARD").outcome());
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "PLAYERS").outcome());
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "BOARD").outcome());
        verify(captures, never()).record(eq(Sport.NFL), any(), eq("BOARD"), any(), any());
        assertTrue(r.failed());
    }

    @Test
    void anFfcFetchFailureIsBestEffortAndDoesNotFailTheRun() {
        when(boardRefresh.run(any())).thenReturn(board(ffcFetchFailed()));

        DailyResult r = service.runAll();

        StepResult b = step(r, Sport.NFL, "BOARD");
        assertEquals(Outcome.DONE_ADP_FAILED_BEST_EFFORT, b.outcome());
        assertTrue(b.detail().contains("fetch failed: 503"), "the gap is visible in the detail");
        verify(captures).record(eq(Sport.NFL), any(), eq("BOARD"), any(), eq(b.detail()));
        assertFalse(r.failed(), "spec amendment 8: a best-effort FFC failure must not fail the run");
    }

    @Test
    void ffcFootballOnlySkipForAnotherSportIsPlainDone() {
        when(boardRefresh.run(any())).thenReturn(board(ffcSkippedForSport()));

        DailyResult r = service.runAll();

        for (Sport sport : Sport.values()) {
            assertEquals(Outcome.DONE, step(r, sport, "BOARD").outcome());
        }
        assertFalse(r.failed());
    }

    @Test
    void theOverallResultFailsIffANonSkippedStepFailed() {
        when(captures.exists(any(), any(), eq("PLAYERS"))).thenReturn(true);
        assertFalse(service.runAll().failed(), "all skipped plus good boards");

        when(boardRefresh.run(Sport.NBA)).thenThrow(new RuntimeException("boom"));
        assertTrue(service.runAll().failed(), "a failed board fails it even when players were skipped");
    }

    @Test
    void thePlayersStepAloneIsWhatTheSetupFlowRuns() {
        StepResult p = service.players(Sport.NBA);

        assertEquals(Outcome.DONE, p.outcome());
        verify(players).ingest(Sport.NBA);
        verifyNoInteractions(boardRefresh);
        verify(captures).record(eq(Sport.NBA), any(), eq("PLAYERS"), any(), anyString());
    }

    @Test
    void aTrendingFailureIsBestEffortAndDoesNotFailTheRun() {
        when(trending.refreshIfStale(any(), any())).thenReturn(TrendingRefresh.Outcome.FAILED);

        DailyResult r = service.runAll();

        assertEquals(Outcome.DONE_TRENDING_FAILED_BEST_EFFORT, step(r, Sport.NFL, "TRENDING").outcome());
        assertFalse(r.failed(), "spec 014: trending is decoration and must not fail the run");
        verify(captures, never()).record(any(), any(), eq("TRENDING"), any(), any());
    }

    @Test
    void trendingReportsDoneWhenFetchedAndSkippedWhenFresh() {
        when(trending.refreshIfStale(eq(Sport.NBA), any())).thenReturn(TrendingRefresh.Outcome.SKIPPED_FRESH);

        DailyResult r = service.runAll();

        assertEquals(Outcome.DONE, step(r, Sport.NFL, "TRENDING").outcome());
        assertEquals(Outcome.SKIPPED_ALREADY_TODAY, step(r, Sport.NBA, "TRENDING").outcome());
    }

    // ---- spec 030 C4: the season-projections step ----

    @Test
    void seasonProjectionsRunsForNbaOnItsNewestSeasonAndNotForNfl() {
        DailyResult r = service.runAll();

        StepResult nba = step(r, Sport.NBA, "SEASON_PROJECTIONS");
        assertEquals(Outcome.DONE, nba.outcome());
        assertEquals("rowsStored=529 rowsDeleted=3", nba.detail());
        verify(seasonProjections).refresh(Sport.NBA, 2026, false);
        verify(seasonProjections, never()).refresh(eq(Sport.NFL), anyInt(), anyBoolean());
        assertTrue(r.steps().stream().noneMatch(s -> s.sport() == Sport.NFL && s.kind().equals("SEASON_PROJECTIONS")));
    }

    @Test
    void aFreshSeasonProjectionSetReportsDoneWithFreshDetail() {
        when(seasonProjections.refresh(any(), anyInt(), anyBoolean())).thenReturn(
                new SeasonProjectionIngestService.Result(false, true, 0, 0, 0, "rotowire", null, null));

        StepResult nba = step(service.runAll(), Sport.NBA, "SEASON_PROJECTIONS");

        assertEquals(Outcome.DONE, nba.outcome());
        assertEquals("fresh", nba.detail());
    }

    @Test
    void aRejectedSeasonProjectionFetchIsBestEffortFailedWithTheReason() {
        when(seasonProjections.refresh(any(), anyInt(), anyBoolean())).thenReturn(
                new SeasonProjectionIngestService.Result(true, false, 0, 0, 0, null, null, "player 9 has gp=3.0"));

        DailyResult r = service.runAll();

        StepResult nba = step(r, Sport.NBA, "SEASON_PROJECTIONS");
        assertEquals(Outcome.DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT, nba.outcome());
        assertEquals("player 9 has gp=3.0", nba.detail());
        assertFalse(r.failed());
    }

    @Test
    void aThrownSeasonProjectionIngestIsBestEffortAndLaterStepsStillRun() {
        when(seasonProjections.refresh(any(), anyInt(), anyBoolean())).thenThrow(new IllegalStateException("sleeper 503"));

        DailyResult r = service.runAll();

        assertEquals(Outcome.DONE_SEASON_PROJECTIONS_FAILED_BEST_EFFORT, step(r, Sport.NBA, "SEASON_PROJECTIONS").outcome());
        assertFalse(r.failed(), "an undocumented endpoint wobbling must not fail the daily refresh");
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "BOARD").outcome());
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "TRENDING").outcome());
    }

    @Test
    void aCompleteSeasonDoesNotNeedProjections() {
        when(seasonState.stateOf(any())).thenReturn(SeasonStateService.SeasonState.COMPLETE);

        StepResult nba = step(service.runAll(), Sport.NBA, "SEASON_PROJECTIONS");

        assertEquals(Outcome.DONE, nba.outcome());
        verify(seasonProjections, never()).refresh(any(), anyInt(), anyBoolean());
    }
}
