package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository.Provenance;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository.ReplaceResult;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** specs/030-post-draft-season-view T008: the ingest's validation rules, with a stubbed client. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeasonProjectionIngestServiceTest {

    @Mock private SleeperProjectionClient client;
    @Mock private PlayerSeasonProjectionRepository repo;

    private SeasonProjectionIngestService service;

    @BeforeEach
    void setUp() {
        service = new SeasonProjectionIngestService(client, repo);
        when(repo.provenance(anyString(), anyInt())).thenReturn(Optional.empty());
        when(repo.replaceAll(anyString(), anyInt(), anyList())).thenAnswer(inv ->
                new ReplaceResult(((List<?>) inv.getArgument(2)).size(), 0));
    }

    private static Map<String, Object> row(String id, Map<String, Object> stats) {
        return Map.of("player_id", id, "company", "rotowire", "last_modified", 1791719411944L, "stats", stats);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStubRowWithOnlyAnAdpIsNotStored() {
        when(client.season("nba", 2026)).thenReturn(List.of(
                row("1", Map.of("adp_std", 55.0)),
                row("2", Map.of("pts", 20.0, "reb", 5.0, "gp", 1.0))));

        var r = service.refresh(Sport.NBA, 2026, false);

        ArgumentCaptor<List<Row>> rows = ArgumentCaptor.forClass(List.class);
        verify(repo).replaceAll(eq("nba"), eq(2026), rows.capture());
        assertEquals(List.of("2"), rows.getValue().stream().map(Row::sleeperPlayerId).toList());
        assertEquals(1, r.rowsStored());
        assertEquals(1, r.skippedNoStats());
        assertEquals("rotowire", r.source());
        assertEquals(Instant.ofEpochMilli(1791719411944L), r.sourceModifiedAt());
        assertTrue(r.fetched());
        assertFalse(r.fresh());
    }

    @Test
    void aRowWithGpOtherThanOneRejectsTheWholeBatch() {
        when(client.season("nba", 2026)).thenReturn(List.of(
                row("1", Map.of("pts", 20.0, "gp", 1.0)),
                row("2", Map.of("pts", 1600.0, "gp", 3.0))));

        var r = service.refresh(Sport.NBA, 2026, false);

        verify(repo, never()).replaceAll(anyString(), anyInt(), anyList());
        assertEquals(0, r.rowsStored());
        assertTrue(r.fetched());
        assertNotNull(r.rejectedReason());
    }

    @Test
    void aFetchThatWouldShrinkTheStoredSetBelowHalfIsRefusedAndKeepsTheRows() {
        when(repo.provenance("nba", 2026)).thenReturn(Optional.of(
                new Provenance("rotowire", Instant.now().minus(java.time.Duration.ofDays(2)), Instant.now().minus(java.time.Duration.ofDays(2)), 100)));
        List<Map<String, Object>> few = new java.util.ArrayList<>();
        for (int i = 0; i < 49; i++) few.add(row("p" + i, Map.of("pts", 10.0, "gp", 1.0)));
        when(client.season("nba", 2026)).thenReturn(few);

        var r = service.refresh(Sport.NBA, 2026, false);

        verify(repo, never()).replaceAll(anyString(), anyInt(), anyList());
        assertEquals(0, r.rowsStored());
        assertNotNull(r.rejectedReason());
    }

    @Test
    void aFetchAtExactlyHalfOfStoredIsAccepted() {
        when(repo.provenance("nba", 2026)).thenReturn(Optional.of(
                new Provenance("rotowire", Instant.now().minus(java.time.Duration.ofDays(2)), Instant.now().minus(java.time.Duration.ofDays(2)), 100)));
        List<Map<String, Object>> half = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) half.add(row("p" + i, Map.of("pts", 10.0, "gp", 1.0)));
        when(client.season("nba", 2026)).thenReturn(half);

        var r = service.refresh(Sport.NBA, 2026, false);

        assertEquals(50, r.rowsStored());
        assertNull(r.rejectedReason());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adpKeysAreStrippedAndTheBasisIsRecordedPerGame() {
        when(client.season("nba", 2026)).thenReturn(List.of(
                row("1", Map.of("pts", 20.0, "adp_std", 12.0, "adp_dd_ppr", 3.0, "gp", 1.0))));

        service.refresh(Sport.NBA, 2026, false);

        ArgumentCaptor<List<Row>> rows = ArgumentCaptor.forClass(List.class);
        verify(repo).replaceAll(eq("nba"), eq(2026), rows.capture());
        Row stored = rows.getValue().getFirst();
        assertFalse(stored.statsJson().contains("adp_"), stored.statsJson());
        assertTrue(stored.statsJson().contains("\"pts\""));
        assertEquals("PER_GAME", stored.basis());
    }

    @Test
    void aFreshStoredSetMakesNoClientCall() {
        when(repo.provenance("nba", 2026)).thenReturn(Optional.of(
                new Provenance("rotowire", Instant.now(), Instant.now(), 529)));

        var r = service.refresh(Sport.NBA, 2026, false);

        verifyNoInteractions(client);
        assertTrue(r.fresh());
        assertFalse(r.fetched());
    }

    @Test
    void forceRefetchesEvenWhenFresh() {
        when(repo.provenance("nba", 2026)).thenReturn(Optional.of(
                new Provenance("rotowire", Instant.now(), Instant.now(), 1)));
        when(client.season("nba", 2026)).thenReturn(List.of(row("1", Map.of("pts", 20.0))));

        var r = service.refresh(Sport.NBA, 2026, true);

        assertTrue(r.fetched());
        verify(repo).replaceAll(eq("nba"), eq(2026), anyList());
    }

    @Test
    void anEmptyFetchNeverWipesWhatIsStored() {
        when(client.season("nba", 2026)).thenReturn(List.of(row("1", Map.of("adp_std", 55.0))));

        service.refresh(Sport.NBA, 2026, false);

        verify(repo, never()).replaceAll(anyString(), anyInt(), anyList());
    }

    @Test
    void nflThrowsWithASentence() {
        var e = assertThrows(IllegalArgumentException.class, () -> service.refresh(Sport.NFL, 2026, false));
        assertTrue(e.getMessage().contains("aren't wired up"), e.getMessage());
        verifyNoInteractions(client);
    }
}
