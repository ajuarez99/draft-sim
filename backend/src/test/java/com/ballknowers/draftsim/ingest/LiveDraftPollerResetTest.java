package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Spec 028: a Sleeper draft that is reset returns to pre_draft with no picks, and
 * stored picks used to stay forever. The deletion rule is deliberately narrow --
 * these tests pin both the rule and, more importantly, every case where nothing
 * may be deleted (a real draft runs through this poller).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveDraftPollerResetTest {

    @Mock private SleeperClient sleeper;
    @Mock private DraftRepository drafts;
    @Mock private ManagerRepository managers;
    @Mock private PlayerRepository players;

    private LiveDraftPoller poller;

    @AfterEach
    void cleanup() {
        if (poller != null) poller.shutdown();
    }

    private static DraftRepository.DraftRow row(String status) {
        return new DraftRepository.DraftRow(1L, 10L, "sd-1", 2026, 15, 14, status, Map.of());
    }

    // ---- the decision rule, in isolation -------------------------------------------------

    @Test
    void ruleClearsOnlyOnPreDraftWithAParsedEmptyList() {
        assertTrue(LiveDraftPoller.shouldClearStalePicks("pre_draft", List.of()));
        assertFalse(LiveDraftPoller.shouldClearStalePicks("drafting", List.of()));
        assertFalse(LiveDraftPoller.shouldClearStalePicks("paused", List.of()));
        assertFalse(LiveDraftPoller.shouldClearStalePicks("complete", List.of()));
        assertFalse(LiveDraftPoller.shouldClearStalePicks(null, List.of()));
        assertFalse(LiveDraftPoller.shouldClearStalePicks("pre_draft", null));
        assertFalse(LiveDraftPoller.shouldClearStalePicks("pre_draft", List.of(Map.of("pick_no", 1))));
        assertFalse(LiveDraftPoller.shouldClearStalePicks("drafting", null));
    }

    // ---- the poller wiring ----------------------------------------------------------------

    private void stub(String status, int storedPicks) {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        when(sleeper.draft("sd-1")).thenReturn(Map.of("status", status));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());
        when(drafts.countPicks(1L)).thenReturn(storedPicks);
    }

    @Test
    void preDraftWithEmptySleeperPicksAndStoredPicksClearsThemAndPublishesZero() throws Exception {
        stub("pre_draft", 42);
        when(sleeper.draftPicks("sd-1")).thenReturn(new ArrayList<>());
        when(drafts.clearPicks(1L)).thenReturn(42);
        var seen = new java.util.concurrent.LinkedBlockingQueue<LiveDraftPoller.LiveSnapshot>();
        poller.subscribe(1L, seen::add);

        LiveDraftPoller.Tick tick = poller.pollOnce(row("pre_draft"));

        assertTrue(tick.keepPolling());
        verify(drafts).clearPicks(1L);
        // The same fan-out a new pick uses: open browsers get picksMade=0.
        LiveDraftPoller.LiveSnapshot snap = seen.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(snap, "a snapshot must be fanned out after the clear");
        assertEquals("pre_draft", snap.status());
        assertEquals(0, snap.picksMade());
        assertEquals(0, snap.lastPickNo());
    }

    @Test
    void preDraftWithNoStoredPicksNeverAsksSleeperAndNeverDeletes() {
        stub("pre_draft", 0);

        poller.pollOnce(row("pre_draft"));

        verify(sleeper, never()).draftPicks(any());
        verify(drafts, never()).clearPicks(anyLong());
    }

    @Test
    void preDraftWhenSleeperPicksFetchFailsDoesNotDeleteAndDoesNotFailTheTick() {
        stub("pre_draft", 42);
        when(sleeper.draftPicks("sd-1")).thenThrow(new IllegalStateException("sleeper 500"));

        LiveDraftPoller.Tick tick = poller.pollOnce(row("pre_draft"));

        assertTrue(tick.keepPolling());
        verify(drafts, never()).clearPicks(anyLong());
    }

    @Test
    void preDraftWhenSleeperPicksBodyIsNullDoesNotDelete() {
        stub("pre_draft", 42);
        when(sleeper.draftPicks("sd-1")).thenReturn(null);

        poller.pollOnce(row("pre_draft"));

        verify(drafts, never()).clearPicks(anyLong());
    }

    @Test
    void draftingWithEmptySleeperPicksNeverDeletes() {
        stub("drafting", 42);
        when(sleeper.draftPicks("sd-1")).thenReturn(new ArrayList<>());

        poller.pollOnce(row("drafting"));

        verify(drafts, never()).clearPicks(anyLong());
    }

    @Test
    void pausedAndCompleteWithEmptySleeperPicksNeverDelete() {
        for (String status : List.of("paused", "complete")) {
            stub(status, 42);
            when(sleeper.draftPicks("sd-1")).thenReturn(new ArrayList<>());
            poller.pollOnce(row(status));
            poller.shutdown();
        }
        verify(drafts, never()).clearPicks(anyLong());
    }
}
