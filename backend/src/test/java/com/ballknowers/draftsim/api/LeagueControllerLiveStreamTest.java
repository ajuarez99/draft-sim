package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SimulationResult;
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

import java.util.ArrayList;
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
        // so the caller can always see the draft. LeagueMembership has its own
        // tests; a mock left unstubbed answers Optional.empty(), which would 404
        // every one of these for the wrong reason.
        //
        // Delegates to the mocked DraftRepository rather than returning a fixed
        // row, so each test's own when(drafts.bySleeperId(...)) still decides
        // what the draft is -- and the "unknown draft is 404" cases keep working,
        // since an id nobody stubbed comes back empty from there too.
        lenient().when(membership.visibleDraft(any(), any()))
                .thenAnswer(inv -> drafts.bySleeperId(inv.getArgument(1)));
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

    // ---- the `state` frame's landed picks -------------------------------------
    //
    // These are what lets the live page name a player without waiting for a
    // simulation. Asserted against statePayload directly; see its javadoc for
    // why the emitter is not a usable seam here.

    private static Player player(long id, String name, Position pos) {
        return new Player(id, Sport.NFL, "s" + id, name, List.of(pos), "SEA", "Active", null, null, null);
    }

    private static LiveDraftPoller.LiveSnapshot snapshot(int picksMade) {
        return new LiveDraftPoller.LiveSnapshot("drafting", picksMade, picksMade, 14, 1);
    }

    /** n picks, alternating between the two mapped slots, players 1..n. */
    private List<DraftRepository.PickRow> picks(int n) {
        List<DraftRepository.PickRow> rows = new ArrayList<>();
        List<Player> pool = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            int slot = i % 2 == 1 ? 1 : 2;
            rows.add(new DraftRepository.PickRow(1L, i, 1, slot, slot == 1 ? 101L : 102L, (long) i, null));
            pool.add(player(i, "Player " + i, Position.RB));
        }
        List<BoardEntry> board = new ArrayList<>();
        for (Player p : pool) board.add(new BoardEntry(p, p.id(), (int) p.id()));
        when(boards.currentBoard(Sport.NFL)).thenReturn(board);
        when(players.findAll(Sport.NFL)).thenReturn(pool);
        when(managers.names()).thenReturn(Map.of(101L, "Allan", 102L, "Sam"));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recentPicks(int stored) {
        LeagueController controller = controller();
        Map<String, Object> payload = controller.statePayload(
                "d1", row("drafting"), snapshot(stored), picks(stored),
                controller.pickNaming(Sport.NFL));
        return (List<Map<String, Object>>) payload.get("recentPicks");
    }

    /**
     * The whole point of the field: a pick that just landed is nameable off the
     * state frame, with no simulation between the poller and the reader.
     */
    @Test
    void theStateFrameNamesThePicksThatLanded() {
        List<Map<String, Object>> recent = recentPicks(3);

        assertEquals(3, recent.size());
        Map<String, Object> newest = recent.get(2);
        assertEquals(3, newest.get("pickNo"));
        assertEquals("Allan", newest.get("manager"));
        assertEquals("Player 3", ((SimulationResult.PlayerRef) newest.get("player")).name());
    }

    /**
     * Capped, and capped at the TAIL -- the newest picks are the ones the feed
     * and the announcement row are for. Truncating the other end would leave the
     * page permanently showing round one.
     */
    @Test
    void onlyTheLastFewPicksRideAlong() {
        List<Map<String, Object>> recent = recentPicks(30);

        assertEquals(12, recent.size());
        assertEquals(19, recent.get(0).get("pickNo"), "oldest of the tail");
        assertEquals(30, recent.get(11).get("pickNo"), "newest pick last -- PickFeed reads oldest-first");
    }

    /** Nothing drafted yet is an empty array, not a missing field. */
    @Test
    void aDraftWithNoPicksSendsAnEmptyArray() {
        LeagueController controller = controller();
        Map<String, Object> payload = controller.statePayload(
                "d1", row("pre_draft"), snapshot(0), List.of(), controller.pickNaming(Sport.NFL));

        assertEquals(List.of(), payload.get("recentPicks"));
    }
}
