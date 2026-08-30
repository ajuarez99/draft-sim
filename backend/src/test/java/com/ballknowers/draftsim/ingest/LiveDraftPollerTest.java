package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * pollOnce is the pure, unit-testable unit per claude/live-poller-plan.md decision 6:
 * one Sleeper fetch, conditionally one upsert, one status write, no sleep.
 */
@ExtendWith(MockitoExtension.class)
class LiveDraftPollerTest {

    @Mock private SleeperClient sleeper;
    @Mock private DraftRepository drafts;
    @Mock private ManagerRepository managers;
    @Mock private PlayerRepository players;

    private LiveDraftPoller poller;

    private static DraftRepository.DraftRow draftRow(String status) {
        return new DraftRepository.DraftRow(1L, 10L, "sleeper-draft-123", 2026, 15, 14,
                status, Map.of("3", 200));
    }

    private static Map<String, Object> rawPick(String playerId, String pickedBy, int draftSlot,
                                                int pickNo, int round) {
        Map<String, Object> p = new HashMap<>();
        p.put("player_id", playerId);
        p.put("picked_by", pickedBy);
        p.put("draft_slot", draftSlot);
        p.put("pick_no", pickNo);
        p.put("round", round);
        return p;
    }

    @AfterEach
    void cleanup() {
        if (poller != null) poller.shutdown();
    }

    @Test
    void pollOnceWithDraftingStatusUpsertsMappedRowsAndReturnsTrue() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("drafting");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "drafting"));
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("p1", "u1", 3, 15, 2)));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u1", 100L));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("p1", 5L));

        boolean keepPolling = poller.pollOnce(draft);

        assertTrue(keepPolling);
        verify(drafts).updateStatus(1L, "drafting");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DraftRepository.PickRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(drafts).upsertPicks(eq(1L), captor.capture());
        List<DraftRepository.PickRow> rows = captor.getValue();
        assertEquals(1, rows.size());
        DraftRepository.PickRow row = rows.get(0);
        assertEquals(1L, row.draftId());
        assertEquals(15, row.pickNo());
        assertEquals(2, row.round());
        assertEquals(3, row.draftSlot());
        assertEquals(100L, row.managerId());   // picked_by wins over slotLookup's 200L
        assertEquals(5L, row.playerId());
        assertNull(row.adpAtTime());
    }

    @Test
    void pollOnceWithPreDraftStatusSkipsPicksButUpdatesStatus() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "pre_draft"));

        boolean keepPolling = poller.pollOnce(draft);

        assertTrue(keepPolling);
        verify(drafts).updateStatus(1L, "pre_draft");
        verify(sleeper, never()).draftPicks(any());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    @Test
    void pollOnceWithCompleteStatusUpdatesStatusOnceAndReturnsFalse() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("complete");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "complete"));

        boolean keepPolling = poller.pollOnce(draft);

        assertFalse(keepPolling);
        verify(drafts, times(1)).updateStatus(1L, "complete");
        verify(sleeper, never()).draftPicks(any());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    @Test
    void trackCalledTwiceStartsExactlyOnePoller() throws InterruptedException {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft");

        // Block the spawned poller thread indefinitely so it stays registered in
        // `active` for the duration of this test, rather than racing to complete
        // and remove itself before the second track() call runs.
        CountDownLatch firstTickStarted = new CountDownLatch(1);
        when(sleeper.draft("sleeper-draft-123")).thenAnswer(inv -> {
            firstTickStarted.countDown();
            Thread.sleep(Duration.ofHours(1));
            return Map.of("status", "pre_draft");
        });

        LiveDraftPoller.TrackResult first = poller.track(draft);
        assertTrue(firstTickStarted.await(5, TimeUnit.SECONDS), "spawned poller never called sleeper.draft()");
        LiveDraftPoller.TrackResult second = poller.track(draft);

        assertTrue(first.started());
        assertFalse(second.started());
        assertEquals("pre_draft", first.status());
        assertEquals("pre_draft", second.status());
    }
}
