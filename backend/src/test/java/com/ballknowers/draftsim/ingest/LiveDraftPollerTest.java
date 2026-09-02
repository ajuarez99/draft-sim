package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        return draftRow(status, Map.of("3", 200));
    }

    private static DraftRepository.DraftRow draftRow(String status, Map<String, Object> slotToManager) {
        return new DraftRepository.DraftRow(1L, 10L, "sleeper-draft-123", 2026, 15, 14,
                status, slotToManager);
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

    /** A 14-seat draft_order the way Sleeper serves it: sleeper user id -> slot. */
    private static Map<String, Object> draftOrderOf(int seats) {
        Map<String, Object> order = new LinkedHashMap<>();
        for (int s = 1; s <= seats; s++) order.put("u" + s, s);
        return order;
    }

    private static Map<String, Long> managersOf(int seats) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int s = 1; s <= seats; s++) out.put("u" + s, 100L + s);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<DraftRepository.PickRow> capturedUpsert() {
        ArgumentCaptor<List<DraftRepository.PickRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(drafts).upsertPicks(eq(1L), captor.capture());
        return captor.getValue();
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

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertTrue(tick.keepPolling());
        assertEquals("drafting", tick.status());
        verify(drafts).updateStatus(1L, "drafting");

        List<DraftRepository.PickRow> rows = capturedUpsert();
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
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertTrue(tick.keepPolling());
        verify(drafts).updateStatus(1L, "pre_draft");
        verify(sleeper, never()).draftPicks(any());
        verify(drafts, never()).upsertPicks(anyLong(), any());
    }

    /**
     * FIX 2. The poller used to return on "complete" BEFORE fetching picks, so the
     * handful of picks made between the last drafting tick and the draft closing
     * were never ingested at all -- permanently missing from draft_pick, with
     * nothing anywhere reporting a gap. upsertPicks is idempotent, so ingesting
     * once more on the way out is free.
     */
    @Test
    void pollOnceWithCompleteStatusStillIngestsPicksOnceThenStops() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("complete");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "complete"));
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("p1", "u1", 3, 210, 15)));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u1", 100L));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("p1", 5L));

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertFalse(tick.keepPolling(), "a complete draft must stop the loop");
        verify(drafts, times(1)).updateStatus(1L, "complete");
        assertEquals(1, capturedUpsert().size(), "the final picks must be ingested before stopping");
    }

    /**
     * FIX 3, and the assertion that matters most: the autopicked pick. West Coast
     * FF 2026 was ingested while Sleeper still returned "draft_order": null, so an
     * EMPTY slot map was persisted. Sleeper leaves picked_by blank on autopicks, so
     * with a frozen empty map every autopick would have landed with manager_id
     * null, been filtered out of allCompletedPicks, and left all 14 seats
     * simulating as league-average bots for the whole draft.
     */
    @Test
    void aDraftOrderArrivingMidPollPersistsTheSeatMapAndResolvesAutopicks() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        // A WRONG stored value at the same slot, not an empty map. With an empty one
        // this test could not tell "derived wins" apart from "derived merged into
        // stored" -- an implementation that preferred a non-empty stored map would
        // have passed it. Seat maps legitimately change (a commissioner
        // re-randomizes pre_draft), so the precedence has to be pinned, not implied.
        DraftRepository.DraftRow draft = draftRow("drafting", Map.of("7", 999L));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "drafting");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("p1", 5L));
        // picked_by blank == autopicked; only the slot map can resolve it.
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("p1", "", 7, 7, 1)));

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertEquals(14, tick.seatsMapped());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(drafts).updateSlotToManager(eq(1L), json.capture());
        Map<String, Object> persisted = JsonUtil.readMap(json.getValue());
        assertEquals(14, persisted.size(), "all 14 seats must be persisted");
        assertEquals(107, ((Number) persisted.get("7")).intValue(),
                "the derived map must overwrite the stored one at slot 7, not defer to it");

        assertEquals(107L, capturedUpsert().get(0).managerId(),
                "an autopicked pick must resolve through the freshly-derived map, not the stale frozen one");
    }

    /** The commissioner usually sets the order before the draft opens, so pre_draft must persist it too. */
    @Test
    void aDraftOrderSetWhileStillPreDraftIsPersistedWithoutFetchingPicks() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertTrue(tick.keepPolling());
        assertEquals(14, tick.seatsMapped());
        verify(drafts).updateSlotToManager(eq(1L), anyString());
        verify(sleeper, never()).draftPicks(any());
    }

    /** A null draft_order must never overwrite a good stored map with an empty one. */
    @Test
    void aNullDraftOrderLeavesTheStoredSeatMapAlone() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of("3", 200));

        Map<String, Object> raw = new HashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", null);   // exactly what Sleeper returns before the order is set
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertEquals(1, tick.seatsMapped(), "falls back to the seat map frozen on the DraftRow");
        verify(drafts, never()).updateSlotToManager(anyLong(), anyString());
    }

    /** A draft_order user with no manager row drops that seat rather than throwing. */
    @Test
    void aDraftOrderUserWithNoManagerRowDropsThatSeatWithoutFailing() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("u1", 1);
        order.put("stranger", 2);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", order);
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u1", 101L));

        LiveDraftPoller.Tick tick = poller.pollOnce(draft);

        assertEquals(1, tick.seatsMapped(), "the unmapped user's seat is dropped, not guessed at");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(drafts).updateSlotToManager(eq(1L), json.capture());
        assertEquals(Map.of("1", 101), JsonUtil.readMap(json.getValue()));
    }

    /**
     * FIX 1, the draft-night one. Thread.sleep used to be the last statement INSIDE
     * the try, so any exception out of pollOnce skipped it entirely and the loop
     * re-entered immediately -- an unthrottled hammer on api.sleeper.app, which
     * trips Sleeper's rate limit, which throws, which sustains the loop.
     *
     * Asserting on elapsed time rather than call count: with no sleep, three
     * failures complete in well under a millisecond, so the wall clock is what
     * distinguishes the two implementations regardless of scheduler noise.
     */
    @Test
    void aTickThatThrowsStillSleepsBeforeTheNextOne() throws InterruptedException {
        Duration interval = Duration.ofMillis(50);
        poller = new LiveDraftPoller(sleeper, drafts, managers, players, interval);
        DraftRepository.DraftRow draft = draftRow("drafting");

        CountDownLatch threeFailures = new CountDownLatch(3);
        when(sleeper.draft("sleeper-draft-123")).thenAnswer(inv -> {
            threeFailures.countDown();
            throw new IllegalStateException("sleeper 500");
        });

        long startedAt = System.nanoTime();
        Thread t = Thread.ofVirtual().start(() -> poller.loop(draft));
        assertTrue(threeFailures.await(10, TimeUnit.SECONDS), "the loop stopped retrying after a failure");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        t.interrupt();
        t.join(Duration.ofSeconds(5));

        // Two gaps between three attempts, and the backoff makes the second one
        // longer -- so >= 2 intervals is a floor, not the expected value.
        assertTrue(elapsedMs >= 2 * interval.toMillis(),
                "three failing ticks took only " + elapsedMs + "ms -- the loop is busy-spinning");
    }

    @Test
    void trackCalledTwiceStartsExactlyOnePollerAndFetchesSleeperOnlyOnce() {
        // A long interval so the spawned loop's own ticks never land during the
        // test; the fetch count below would be meaningless otherwise.
        poller = new LiveDraftPoller(sleeper, drafts, managers, players, Duration.ofSeconds(30));
        DraftRepository.DraftRow draft = draftRow("pre_draft");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "pre_draft"));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());

        LiveDraftPoller.TrackResult first = poller.track(draft);
        LiveDraftPoller.TrackResult second = poller.track(draft);

        assertTrue(first.started());
        assertFalse(second.started(), "the second /track must not spawn a second poller");
        assertEquals("pre_draft", first.status());
        assertEquals("pre_draft", second.status());
        assertTrue(second.pollerRunning(), "the first call's poller is still running");

        // The assertion that was missing, and the reason the bug was invisible:
        // track() ran its synchronous tick BEFORE the idempotence check, so a second
        // /track did a second full Sleeper fetch on the request thread, racing the
        // poll loop. /track is the only way to read seatsMapped, so an operator
        // refreshing it by hand doubled Sleeper load mid-draft.
        verify(sleeper, times(1)).draft("sleeper-draft-123");
    }

    /**
     * A complete draft deliberately spawns no thread, so started=false -- which the
     * controller used to read as "somebody else is already tracking it". Both
     * `tracking` and `alreadyTracking` came back true for a draft nothing was
     * polling, on the one endpoint whose job is draft-night diagnosis.
     */
    @Test
    void trackOnACompleteDraftReportsNoPollerRunning() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players, Duration.ofSeconds(30));
        DraftRepository.DraftRow draft = draftRow("complete");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "complete"));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of());
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of());

        LiveDraftPoller.TrackResult result = poller.track(draft);

        assertFalse(result.started());
        assertFalse(result.pollerRunning(), "nothing is polling a finished draft");
        assertTrue(result.observed());
        assertEquals("complete", result.status());
        assertFalse(poller.isTracking(1L));
    }

    /**
     * /track used to report draft.status() -- whatever the DB happened to hold when
     * the row was read -- which made it useless as a draft-night diagnostic: it
     * would report "pre_draft" for a draft that had been live for an hour. It now
     * runs one tick synchronously first and reports what Sleeper actually said.
     */
    @Test
    void trackReportsTheFreshlyObservedStatusAndSeatCountNotTheStoredOne() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players, Duration.ofSeconds(30));
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());   // stale stored state

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "drafting");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of());
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of());

        LiveDraftPoller.TrackResult result = poller.track(draft);

        assertEquals("drafting", result.status());
        assertEquals(14, result.seatsMapped());
    }

    /** A Sleeper outage at /track time must still start the poller rather than 500. */
    @Test
    void trackStillStartsThePollerWhenTheSynchronousTickThrows() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players, Duration.ofSeconds(30));
        DraftRepository.DraftRow draft = draftRow("pre_draft");

        when(sleeper.draft("sleeper-draft-123")).thenThrow(new IllegalStateException("sleeper down"));

        LiveDraftPoller.TrackResult result = poller.track(draft);

        assertTrue(result.started());
        assertEquals("pre_draft", result.status(), "falls back to the stored status when Sleeper is unreachable");
        // The whole point of ticking synchronously was "don't report pre_draft for a
        // draft that has been live for an hour". One Sleeper hiccup silently
        // reintroduces exactly that, so the fallback has to be labelled.
        assertFalse(result.observed(),
                "a status that came from the DB rather than Sleeper must not claim to be observed");
    }

    /**
     * The total-failure case, and it used to be the ONLY one that logged nothing:
     * refreshSeatMap returned early on an empty derived map, jumping past the
     * logging block that exists to report exactly this. Zero seats mapped means
     * every seat simulates as an unattributed league-average bot, silently, every
     * 10 seconds, all night.
     */
    @Test
    void zeroSeatsMappedLogsAnErrorOnceRatherThanSilentlyForever() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());   // nothing stored either

        Map<String, Object> raw = new HashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", null);   // exactly what Sleeper serves before the order is set
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        Logger pollerLog = (Logger) LoggerFactory.getLogger(LiveDraftPoller.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        pollerLog.addAppender(appender);
        try {
            poller.pollOnce(draft);
            poller.pollOnce(draft);
            poller.pollOnce(draft);
        } finally {
            pollerLog.detachAppender(appender);
        }

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(),
                "exactly one ERROR: silent is the bug, and 360 an hour is a different bug. got: "
                        + appender.list);
        String message = errors.get(0).getFormattedMessage();
        assertTrue(message.contains("0 of 14 seats mapped"), message);
    }

    @Test
    void subscribersSeeEveryTickAndUnsubscribeRemovesThem() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("drafting", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "drafting");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("p1", 5L));
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("p1", "", 7, 7, 1)));

        List<LiveDraftPoller.LiveSnapshot> seen = new ArrayList<>();
        Runnable unsubscribe = poller.subscribe(1L, seen::add);
        assertEquals(1, poller.listenerCount(1L));

        poller.pollOnce(draft);
        assertEquals(1, seen.size());
        LiveDraftPoller.LiveSnapshot s = seen.get(0);
        assertEquals("drafting", s.status());
        assertEquals(1, s.picksMade());
        assertEquals(7, s.lastPickNo());
        assertEquals(14, s.seatsMapped());
        // 1 pick made at 14 teams -> pick 2 is next, still in round 1, so slot 2.
        assertEquals(2, s.onTheClockSlot());

        unsubscribe.run();
        assertEquals(0, poller.listenerCount(1L));
        poller.pollOnce(draft);
        assertEquals(1, seen.size(), "an unsubscribed listener must stop receiving");
    }

    /**
     * The most important defensive line in the live-stream work: a dead browser tab
     * throwing out of its listener must never take down draft-night ingest. The
     * listener is dropped; the tick and every other listener carry on.
     */
    @Test
    void aThrowingListenerIsDroppedAndDoesNotBreakTheTick() {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        List<LiveDraftPoller.LiveSnapshot> healthy = new ArrayList<>();
        poller.subscribe(1L, s -> { throw new IllegalStateException("ResponseBodyEmitter has already completed"); });
        poller.subscribe(1L, healthy::add);
        assertEquals(2, poller.listenerCount(1L));

        LiveDraftPoller.Tick tick = assertDoesNotThrow(() -> poller.pollOnce(draft));

        assertTrue(tick.keepPolling(), "the tick must survive a listener blowing up");
        assertEquals(1, poller.listenerCount(1L), "the throwing listener must be dropped");
        assertEquals(1, healthy.size(), "the healthy listener still gets its snapshot");

        poller.pollOnce(draft);
        assertEquals(2, healthy.size());
    }
}
