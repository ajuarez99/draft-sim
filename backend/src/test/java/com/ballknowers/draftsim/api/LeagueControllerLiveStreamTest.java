package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GET /api/drafts/{id}/live-stream. A REAL LiveDraftPoller (with mocked Sleeper and
 * repositories) rather than a mock poller, because the thing worth pinning here is
 * the interaction between the endpoint's emitter lifecycle and the poller's
 * listener registry -- specifically that a finished stream leaves nothing behind.
 * A listener leaked at 8:15 PM grows for three hours.
 */
@ExtendWith(MockitoExtension.class)
class LeagueControllerLiveStreamTest {

    @Mock private LeagueRepository leagues;
    @Mock private DraftRepository drafts;
    @Mock private ProfileService profiles;
    @Mock private BoardService boards;
    @Mock private ManagerRepository managers;
    @Mock private PlayerRepository players;
    @Mock private OwnerProperties owner;
    @Mock private LeagueMembership membership;
    @Mock private SleeperClient sleeper;

    private LiveDraftPoller poller;

    @AfterEach
    void cleanup() {
        if (poller != null) poller.shutdown();
    }

    private LeagueController controller() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        // These tests are about each endpoint's own behavior, not about scoping,
        // so the caller can always see the league. LeagueMembership has its own
        // tests; a mock left unstubbed would answer false and fail every one of
        // these for the wrong reason.
        lenient().when(membership.canSee(any(), anyLong())).thenReturn(true);
        return new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner,
                membership);
    }

    private static DraftRepository.DraftRow row(String status) {
        return new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, status,
                Map.of("1", 101, "2", 102));
    }

    @Test
    void anUnknownDraftIs404LikeSeats() {
        when(drafts.bySleeperId("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller().liveStream("nope", null).getStatusCode().value());
    }

    /**
     * A complete draft gets one state and is closed, so the emitter's onCompletion
     * has to unsubscribe. If it didn't, every page open would add a listener the
     * poll loop keeps calling forever.
     */
    @Test
    void aCompletedStreamLeavesNoListenerBehind() {
        DraftRepository.DraftRow draft = row("complete");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(drafts.picks(1L)).thenReturn(List.of());

        ResponseEntity<SseEmitter> response = controller().liveStream("d1", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, poller.listenerCount(1L),
                "the emitter completed, so its listener must already be gone");
        // A complete draft must not be auto-tracked: the stream closes, EventSource
        // reconnects, and an auto-track on every reconnect is a slow hammer on
        // Sleeper for a draft that will never change again.
        verify(sleeper, never()).draft(any());
    }

    /**
     * The draft finishing under an open stream must close the emitter AND drop its
     * listener. Driven through the real publish path rather than by calling
     * emitter.complete() directly, because Spring only wires the onCompletion
     * callback once the async response is initialized -- a unit test calling
     * complete() on a bare emitter would prove nothing about production.
     */
    @Test
    void aDraftGoingCompleteUnderAnOpenStreamUnsubscribes() throws InterruptedException {
        DraftRepository.DraftRow draft = row("drafting");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(drafts.picks(1L)).thenReturn(List.of());
        // The stored row still says "drafting", so the stream subscribes; Sleeper
        // says the draft closed. A complete tick spawns no poll thread, which is
        // what lets the second track() below run another tick (and so another
        // publish) through the public API.
        when(sleeper.draft("d1")).thenReturn(Map.of("status", "complete"));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of());
        when(sleeper.draftPicks("d1")).thenReturn(List.of());

        ResponseEntity<SseEmitter> response = controller().liveStream("d1", null);
        assertNotNull(response.getBody());
        assertEquals(1, poller.listenerCount(1L), "a live draft's stream subscribes");

        poller.track(draft);

        // Awaited rather than asserted outright: LiveDraftPoller hands each
        // subscriber its snapshot on that subscriber's own virtual thread (so one
        // stalled viewer cannot park the poll loop and stop ingest for the whole
        // league), which means the unsubscribe this test is about happens just
        // after track() returns rather than inside it. The guarantee is unchanged
        // -- the listener must go -- only the instant it can be observed moved.
        awaitListenerCount(0, "a leaked listener grows for three hours");
    }

    private void awaitListenerCount(int expected, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && poller.listenerCount(1L) != expected) {
            Thread.sleep(10);
        }
        assertEquals(expected, poller.listenerCount(1L), message);
    }

    /**
     * "The failure Allan cannot afford is: I opened the live page, it looked fine,
     * and nothing was polling."
     */
    @Test
    void openingTheStreamStartsTrackingADraftNothingIsPolling() {
        DraftRepository.DraftRow draft = row("pre_draft");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(drafts.picks(1L)).thenReturn(List.of());
        when(sleeper.draft("d1")).thenReturn(Map.of("status", "pre_draft"));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());

        LeagueController controller = controller();
        assertFalse(poller.isTracking(1L));

        controller.liveStream("d1", null);

        assertTrue(poller.isTracking(1L), "opening the live page must start the poller");
    }

    /** Sleeper being down must not stop the page from painting. */
    @Test
    void aFailedAutoTrackStillOpensTheStream() {
        DraftRepository.DraftRow draft = row("pre_draft");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(drafts.picks(1L)).thenReturn(List.of());
        when(sleeper.draft("d1")).thenThrow(new IllegalStateException("sleeper down"));

        ResponseEntity<SseEmitter> response = controller().liveStream("d1", null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

}
