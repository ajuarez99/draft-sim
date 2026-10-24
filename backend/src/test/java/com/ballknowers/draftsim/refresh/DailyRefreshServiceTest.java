package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardRefresh;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.FfcAdpService;
import com.ballknowers.draftsim.engine.SeasonStateService;
import com.ballknowers.draftsim.ingest.PlayerIngestService;
import com.ballknowers.draftsim.ingest.ProjectionSnapshotService;
import com.ballknowers.draftsim.ingest.SeasonProjectionIngestService;
import com.ballknowers.draftsim.ingest.SleeperAdpCaptureService;
import com.ballknowers.draftsim.ingest.YahooAdpService;
import com.ballknowers.draftsim.store.BoardRepository;
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
    @Mock private ProjectionSnapshotService projectionSnapshots;
    @Mock private SleeperAdpCaptureService adpCapture;
    @Mock private YahooAdpService yahooAdp;
    @Mock private BoardRepository boards;

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
        service = new DailyRefreshService(players, boardRefresh, captures, trending, seasonProjections, leagues, seasonState,
                projectionSnapshots, adpCapture, yahooAdp, boards);
        when(adpCapture.fetchAndCapture(any(), anyInt())).thenReturn(new SleeperAdpCaptureService.Result(800, 900, 2, null));
        when(yahooAdp.ingest(any())).thenReturn(new YahooAdpService.Result(186, 186, 186, 0, null));
        when(trending.refreshIfStale(any(), any())).thenReturn(TrendingRefresh.Outcome.DONE);
        when(players.ingest(any())).thenReturn(new PlayerIngestService.Result(2091, 300, true, 0));
        when(boardRefresh.run(any())).thenReturn(board(ffcOk()));
        when(leagues.all()).thenReturn(List.of(nbaLeague(2026), nbaLeague(2025)));
        when(seasonState.stateOf(any())).thenReturn(SeasonStateService.SeasonState.DRAFTED_UNSCORED);
        when(projectionSnapshots.capture(any(), any())).thenReturn(
                new ProjectionSnapshotService.Result("nba", 2026, List.of(1), 936, 0, 0));
        when(seasonProjections.refresh(any(), anyInt(), anyBoolean())).thenReturn(
                new SeasonProjectionIngestService.Result(true, false, 529, 3, 1591, "rotowire", null, null, 0));
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
                new SeasonProjectionIngestService.Result(false, true, 0, 0, 0, "rotowire", null, null, 0));

        StepResult nba = step(service.runAll(), Sport.NBA, "SEASON_PROJECTIONS");

        assertEquals(Outcome.DONE, nba.outcome());
        assertEquals("fresh", nba.detail());
    }

    @Test
    void aRejectedSeasonProjectionFetchIsBestEffortFailedWithTheReason() {
        when(seasonProjections.refresh(any(), anyInt(), anyBoolean())).thenReturn(
                new SeasonProjectionIngestService.Result(true, false, 0, 0, 0, null, null, "player 9 has gp=3.0", 0));

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

    // ---- spec 046 US1: the ADP_CAPTURE and YAHOO_ADP steps ----

    @Test
    void adpCaptureAndYahooRunForNbaOnlyAndFetchTheNewestNbaSeason() {
        DailyResult r = service.runAll();

        StepResult adp = step(r, Sport.NBA, "ADP_CAPTURE");
        assertEquals(Outcome.DONE, adp.outcome());
        assertEquals("dynastyRows=800 redraftRows=900 unmapped=2", adp.detail());
        verify(adpCapture).fetchAndCapture(Sport.NBA, 2026);
        StepResult yahoo = step(r, Sport.NBA, "YAHOO_ADP");
        assertEquals(Outcome.DONE, yahoo.outcome());
        assertEquals("fetched=186 rows=186 unmatched=0", yahoo.detail());
        assertTrue(r.steps().stream().noneMatch(s -> s.sport() == Sport.NFL
                && (s.kind().equals("ADP_CAPTURE") || s.kind().equals("YAHOO_ADP"))));
        // The gate is latestCapture == today, never a daily_capture row (its check constraint is PLAYERS/BOARD only).
        verify(captures, never()).record(any(), any(), eq("ADP_CAPTURE"), any(), any());
        verify(captures, never()).record(any(), any(), eq("YAHOO_ADP"), any(), any());
    }

    @Test
    void adpCaptureIsGatedOnAnNbaLeagueExistingNotOnAnInProgressSeason() {
        // A completed season: SEASON_PROJECTIONS has nothing to do, but dynasty drafts happen in the offseason.
        when(seasonState.stateOf(any())).thenReturn(SeasonStateService.SeasonState.COMPLETE);

        DailyResult r = service.runAll();

        verify(seasonProjections, never()).refresh(any(), anyInt(), anyBoolean());
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "ADP_CAPTURE").outcome());
        verify(adpCapture).fetchAndCapture(Sport.NBA, 2026);
    }

    @Test
    void adpCaptureWithNoNbaLeagueDoesNothing() {
        when(leagues.all()).thenReturn(List.of());

        StepResult adp = step(service.runAll(), Sport.NBA, "ADP_CAPTURE");

        assertEquals(Outcome.DONE, adp.outcome());
        verifyNoInteractions(adpCapture);
    }

    @Test
    void bothStepsSkipWhenTodaysCaptureAlreadyExists() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(boards.latestCapture(Sport.NBA, BoardRepository.SOURCE_SLEEPER_DYNASTY)).thenReturn(java.util.Optional.of(today));
        when(boards.latestCapture(Sport.NBA, BoardRepository.SOURCE_SLEEPER_REDRAFT)).thenReturn(java.util.Optional.of(today));
        when(boards.latestCapture(Sport.NBA, BoardRepository.SOURCE_YAHOO)).thenReturn(java.util.Optional.of(today));

        DailyResult r = service.runAll();

        assertEquals(Outcome.SKIPPED_ALREADY_TODAY, step(r, Sport.NBA, "ADP_CAPTURE").outcome());
        assertEquals(Outcome.SKIPPED_ALREADY_TODAY, step(r, Sport.NBA, "YAHOO_ADP").outcome());
        verifyNoInteractions(adpCapture, yahooAdp);
    }

    @Test
    void anEarlierCaptureDoesNotSkipTheStep() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        when(boards.latestCapture(Sport.NBA, BoardRepository.SOURCE_YAHOO)).thenReturn(java.util.Optional.of(yesterday));

        service.runAll();

        verify(yahooAdp).ingest(Sport.NBA);
    }

    @Test
    void aRejectedOrThrownAdpStepIsBestEffortAndNeverFailsTheRun() {
        when(adpCapture.fetchAndCapture(any(), anyInt()))
                .thenReturn(new SleeperAdpCaptureService.Result(0, 0, 0, "sleeper_dynasty had 3 rows against 800 stored; kept the stored capture"));
        when(yahooAdp.ingest(any())).thenThrow(new IllegalStateException("yahoo 503"));

        DailyResult r = service.runAll();

        StepResult adp = step(r, Sport.NBA, "ADP_CAPTURE");
        assertEquals(Outcome.DONE_ADP_CAPTURE_FAILED_BEST_EFFORT, adp.outcome());
        assertTrue(adp.detail().contains("kept the stored capture"));
        assertEquals(Outcome.DONE_YAHOO_ADP_FAILED_BEST_EFFORT, step(r, Sport.NBA, "YAHOO_ADP").outcome());
        assertFalse(r.failed());
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "PROJECTION_SNAPSHOT").outcome());
    }

    @Test
    void aYahooRejectionIsReportedWithItsReason() {
        when(yahooAdp.ingest(any())).thenReturn(new YahooAdpService.Result(0, 0, 0, 0, "Yahoo fetch failed: timeout"));

        StepResult yahoo = step(service.runAll(), Sport.NBA, "YAHOO_ADP");

        assertEquals(Outcome.DONE_YAHOO_ADP_FAILED_BEST_EFFORT, yahoo.outcome());
        assertEquals("Yahoo fetch failed: timeout", yahoo.detail());
    }

    // ---- spec 036 US1: the PROJECTION_SNAPSHOT step ----

    @Test
    void projectionSnapshotRunsForBothSportsAndReportsItsCounts() {
        DailyResult r = service.runAll();

        for (Sport sport : Sport.values()) {
            StepResult s = step(r, sport, "PROJECTION_SNAPSHOT");
            assertEquals(Outcome.DONE, s.outcome());
            assertEquals("weeks=[1] rowsWritten=936 rowsFrozenSkipped=0 rowsNotPreGame=0", s.detail());
            verify(projectionSnapshots).capture(eq(sport), any());
        }
        verify(captures, never()).record(any(), any(), eq("PROJECTION_SNAPSHOT"), any(), any());
        assertFalse(r.failed());
    }

    @Test
    void aSportWithNoSeasonToCaptureIsPlainDone() {
        when(projectionSnapshots.capture(eq(Sport.NFL), any())).thenReturn(
                new ProjectionSnapshotService.Result("nfl", null, List.of(), 0, 0, 0));

        StepResult s = step(service.runAll(), Sport.NFL, "PROJECTION_SNAPSHOT");

        assertEquals(Outcome.DONE, s.outcome());
        assertEquals("no current-season league needs projections", s.detail());
    }

    @Test
    void aThrownSnapshotIsBestEffortAndDoesNotFailTheRun() {
        when(projectionSnapshots.capture(any(), any())).thenThrow(new IllegalStateException("sleeper 503"));

        DailyResult r = service.runAll();

        StepResult s = step(r, Sport.NBA, "PROJECTION_SNAPSHOT");
        assertEquals(Outcome.DONE_PROJECTION_SNAPSHOT_FAILED_BEST_EFFORT, s.outcome());
        assertTrue(s.detail().contains("IllegalStateException"));
        assertFalse(r.failed(), "an undocumented endpoint wobbling must not fail the daily refresh");
        assertEquals(Outcome.DONE, step(r, Sport.NBA, "SEASON_PROJECTIONS").outcome());
    }
}
