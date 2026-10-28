package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Superlative;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.TrendingPickupDetail;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository.TrendingSnapshotHeader;
import com.ballknowers.draftsim.store.SportTrendingRepository.TrendingSnapshotRank;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The input-gathering half of TRENDING_TONY (review B2): the ESPN gate, the empty-transactions gate, which
 * adds count, the header date range, the picked-up player ids, and the sport code handed to the repository.
 * A Mockito stand-in for {@link SportTrendingRepository} -- no Postgres -- so what the service asks for is
 * asserted directly (the repository's own SQL is covered by {@code SportTrendingRepositoryIT}).
 */
class SeasonSuperlativesTrendingTonyGatherTest {

    private static final Instant T1 = Instant.parse("2026-10-28T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-30T18:00:00Z");

    private static LeagueRow league(Sport sport, Platform platform, String status) {
        return new LeagueRow(1L, sport, "sl1", "L", 2026, 3, List.of(), 0.0, null, status, platform, null,
                List.of(), List.of());
    }

    private static LeagueTransactionRepository.Row tx(String type, String status, String addsJson, Instant at, int week) {
        return new LeagueTransactionRepository.Row(1L, 2026, week, "t" + addsJson + at, type, status, 1, null,
                addsJson, null, null, at, null, null);
    }

    private static Superlative gather(SportTrendingRepository repo, LeagueRow league,
                                      List<LeagueTransactionRepository.Row> rows) {
        return SeasonSuperlativesService.trendingTonySuperlative(repo, league, rows, 10, Map.of(), Set.of(1, 2, 3),
                Map.of(1, "Team 1"), Map.of(), Map.of());
    }

    @Test
    void asksTheRepositoryForTheLowercaseSportCodeAWidenedRangeAndOnlyCountedPickupsPlayers() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);
        OffsetDateTime s1 = T1.plusSeconds(3600).atOffset(ZoneOffset.UTC);
        OffsetDateTime s2 = T2.plusSeconds(3600).atOffset(ZoneOffset.UTC);
        when(repo.snapshotHeaders(eq("nba"), any(), any())).thenReturn(List.of(
                new TrendingSnapshotHeader(10L, s1, 24, 25), new TrendingSnapshotHeader(11L, s2, 24, 25)));
        when(repo.snapshotRanks(any(), eq(TrendingPickups.TOP_N), any())).thenReturn(List.of(
                new TrendingSnapshotRank(10L, "p1", 3, 500), new TrendingSnapshotRank(11L, "p2", 5, 400)));

        Superlative s = gather(repo, league(Sport.NBA, Platform.SLEEPER, "in_season"), List.of(
                tx("WAIVER", "complete", "{\"p1\":1}", T1, 1),
                tx("FREE_AGENT", "complete", "{\"p2\":1}", T2, 2),
                tx("TRADE", "complete", "{\"p3\":1}", T2, 2),          // not a pickup
                tx("WAIVER", "failed", "{\"p4\":1}", T2, 2)));         // not complete

        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).snapshotHeaders(eq("nba"), from.capture(), to.capture());
        assertEquals(T1.minusSeconds(48 * 3600L), from.getValue().toInstant());
        assertEquals(T2.plusSeconds(48 * 3600L), to.getValue().toInstant());

        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(repo).snapshotRanks(eq(List.of(10L, 11L)), eq(15), ids.capture());
        assertEquals(Set.of("p1", "p2"), Set.copyOf(ids.getValue()));
        verify(repo, never()).earliestSnapshotAt(any());

        assertTrue(s.available());
        assertEquals(2.0, s.value());
        assertEquals(2, s.trendingCoverage().judged());
        assertEquals(2, s.trendingCoverage().totalPickups());
        assertEquals(T1.atOffset(ZoneOffset.UTC), s.trendingCoverage().judgingSinceAt());
        var d = (TrendingPickupDetail) s.detail().get(0);
        assertEquals("p1", d.playerId());
        assertEquals(3, d.trendingRank());
        assertEquals(s1, d.snapshotAt());
    }

    @Test
    void anEspnLeagueIsUnavailableWithoutTouchingTheRepository() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);

        Superlative s = gather(repo, league(Sport.NBA, Platform.ESPN, "in_season"),
                List.of(tx("WAIVER", "complete", "{\"p1\":1}", T1, 1)));

        assertFalse(s.available());
        assertEquals("Trending data comes from Sleeper; this league is on ESPN.", s.reason());
        verifyNoInteractions(repo);
    }

    @Test
    void noTransactionsIsUnavailableWithoutTouchingTheRepository() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);

        Superlative s = gather(repo, league(Sport.NFL, Platform.SLEEPER, "in_season"), List.of());

        assertFalse(s.available());
        assertEquals("Transactions for this season haven't loaded yet.", s.reason());
        verifyNoInteractions(repo);
    }

    @Test
    void aLiveSeasonWhoseHistoryBeganAfterEveryPickupReportsTheEarliestSnapshotAndTheNothingYetReason() {
        SportTrendingRepository repo = mock(SportTrendingRepository.class);
        when(repo.snapshotHeaders(eq("nfl"), any(), any())).thenReturn(List.of());
        when(repo.snapshotRanks(any(), anyInt(), any())).thenReturn(List.of());
        when(repo.earliestSnapshotAt("nfl")).thenReturn(Optional.of(OffsetDateTime.parse("2026-11-20T09:00:00Z")));

        Superlative s = gather(repo, league(Sport.NFL, Platform.SLEEPER, "in_season"),
                List.of(tx("WAIVER", "complete", "{\"p1\":1}", T1, 1)));

        assertFalse(s.available());
        assertEquals("Nothing could be judged yet: trending history began on 2026-11-20 (UTC), "
                + "and no pickup since has a list within a day of it.", s.reason());
        // the same data on a finished season is the "wasn't being recorded" claim
        Superlative done = gather(repo, league(Sport.NFL, Platform.SLEEPER, "complete"),
                List.of(tx("WAIVER", "complete", "{\"p1\":1}", T1, 1)));
        assertEquals("Trending history wasn't being recorded yet for this season.", done.reason());
    }
}
