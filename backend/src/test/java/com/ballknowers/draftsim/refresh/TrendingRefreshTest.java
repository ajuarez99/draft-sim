package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** specs/014 T030: the trending fetch's gate, parse and failure handling, with Sleeper and the store mocked. */
class TrendingRefreshTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private SleeperClient sleeper;
    private SportTrendingRepository repo;
    private TrendingRefresh refresh;

    @BeforeEach
    void setUp() {
        sleeper = mock(SleeperClient.class);
        repo = mock(SportTrendingRepository.class);
        refresh = new TrendingRefresh(sleeper, repo);
        when(repo.read(anyString())).thenReturn(Optional.empty());
        when(sleeper.trendingAdds(anyString(), anyInt(), anyInt())).thenReturn(List.of(
                Map.of("player_id", "4046", "count", 541000),
                Map.of("player_id", "6794", "count", 120)));
        when(sleeper.state(anyString())).thenReturn(Map.of("league_season", "2026", "season_start_date", "2026-09-10"));
    }

    private static SportTrendingRepository.Snapshot fetchedAgo(Duration ago) {
        return new SportTrendingRepository.Snapshot(OffsetDateTime.ofInstant(NOW.minus(ago), ZoneOffset.UTC),
                24, 2026, null, null, null, List.of());
    }

    @Test
    void aListFetchedWithinTheHourIsLeftAloneAndSleeperIsNotCalled() {
        when(repo.read("nfl")).thenReturn(Optional.of(fetchedAgo(Duration.ofMinutes(59))));

        assertEquals(TrendingRefresh.Outcome.SKIPPED_FRESH, refresh.refreshIfStale(Sport.NFL, NOW));

        verifyNoInteractions(sleeper);
        verify(repo, never()).replace(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aStaleListFetchesOnceAndStoresTheParsedSeasonAndStartDate() {
        when(repo.read("nfl")).thenReturn(Optional.of(fetchedAgo(Duration.ofMinutes(61))));

        assertEquals(TrendingRefresh.Outcome.DONE, refresh.refreshIfStale(Sport.NFL, NOW));

        verify(sleeper, times(1)).trendingAdds("nfl", 24, 25);
        verify(sleeper, times(1)).state("nfl");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SportTrendingRepository.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(repo).replace(eq("nfl"), eq(24), eq(2026), eq(LocalDate.of(2026, 9, 10)), entries.capture(),
                eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        assertEquals(List.of(new SportTrendingRepository.Entry("4046", 541000),
                new SportTrendingRepository.Entry("6794", 120)), entries.getValue());
        verify(repo, never()).recordFailure(any(), any(), any());
    }

    @Test
    void aSportNeverFetchedFetches() {
        assertEquals(TrendingRefresh.Outcome.DONE, refresh.refreshIfStale(Sport.NBA, NOW));
        verify(sleeper).trendingAdds("nba", 24, 25);
    }

    @Test
    void aRowThatOnlyEverFailedHasNoFetchedAtAndFetches() {
        when(repo.read("nba")).thenReturn(Optional.of(new SportTrendingRepository.Snapshot(
                null, 24, null, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), "boom", List.of())));

        assertEquals(TrendingRefresh.Outcome.DONE, refresh.refreshIfStale(Sport.NBA, NOW));
    }

    @Test
    void aTrendingFailureIsRecordedAndNothingEscapes() {
        when(sleeper.trendingAdds(anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("sleeper 503"));

        TrendingRefresh.Outcome outcome = assertDoesNotThrow(() -> refresh.refreshIfStale(Sport.NFL, NOW));

        assertEquals(TrendingRefresh.Outcome.FAILED, outcome);
        verify(repo).recordFailure(eq("nfl"), eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                argThat(r -> r.contains("sleeper 503")));
        verify(repo, never()).replace(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aFailureToStoreIsAlsoRecordedAsAFailureAndDoesNotEscape() {
        doThrow(new IllegalStateException("db down")).when(repo).replace(any(), anyInt(), any(), any(), any(), any());

        assertEquals(TrendingRefresh.Outcome.FAILED, assertDoesNotThrow(() -> refresh.refreshIfStale(Sport.NFL, NOW)));
        verify(repo).recordFailure(eq("nfl"), any(), any());
    }

    @Test
    void aFailureToReadTheStoredStateDoesNotEscape() {
        when(repo.read("nfl")).thenThrow(new IllegalStateException("db down"));

        assertEquals(TrendingRefresh.Outcome.FAILED, assertDoesNotThrow(() -> refresh.refreshIfStale(Sport.NFL, NOW)));
    }

    @Test
    void aMissingSeasonStartDateIsNullNotAGuess() {
        when(sleeper.state(anyString())).thenReturn(Map.of("league_season", "2026"));

        assertEquals(TrendingRefresh.Outcome.DONE, refresh.refreshIfStale(Sport.NFL, NOW));

        verify(repo).replace(eq("nfl"), eq(24), eq(2026), isNull(), any(), any());
    }

    @Test
    void anUnparseableStateYieldsNullsRatherThanAFailure() {
        when(sleeper.state(anyString())).thenReturn(Map.of("league_season", "soon", "season_start_date", "tomorrow"));

        assertEquals(TrendingRefresh.Outcome.DONE, refresh.refreshIfStale(Sport.NFL, NOW));

        verify(repo).replace(eq("nfl"), eq(24), isNull(), isNull(), any(), any());
    }
}
