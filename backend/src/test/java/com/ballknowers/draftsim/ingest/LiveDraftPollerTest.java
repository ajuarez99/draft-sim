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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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
    void pollOnceResolvesTheDraftsOwnSportRatherThanAssumingFootball() {
        // The bug this pins (claude/merge-review-multi-sport.md B2): the poller
        // used to resolve every pick through idsBySleeperId(Sport.NFL). NBA
        // sleeper ids miss that map, PickMapper never guesses, and the first
        // `drafting` tick would write a full draft's worth of rows with
        // player_id = null. It fires on NBA draft night specifically, and the
        // 2026 NBA draft is already registered for polling.
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("drafting");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "drafting"));
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("nba-p1", "u1", 3, 15, 2)));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u1", 100L));
        when(drafts.sportOf(1L)).thenReturn(java.util.Optional.of(Sport.NBA));
        when(players.idsBySleeperId(Sport.NBA)).thenReturn(Map.of("nba-p1", 77L));

        poller.pollOnce(draft);

        assertEquals(77L, capturedUpsert().get(0).playerId(),
                "an NBA pick must resolve through the NBA id map, not be written as null");
        // Never even asked for the football map -- and it matters that it did
        // not: 753 sleeper ids exist in both sports, so a football lookup here
        // would not merely miss, it could return the wrong player.
        verify(players, never()).idsBySleeperId(Sport.NFL);
    }

    @Test
    void pollOnceFallsBackToFootballOnlyWhenTheLeagueRowIsGone() {
        // Unreachable through the foreign key; asserted so the fallback is a
        // decision on record rather than whatever Optional.orElse happened to do.
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("drafting");

        when(sleeper.draft("sleeper-draft-123")).thenReturn(Map.of("status", "drafting"));
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("p1", "u1", 3, 15, 2)));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of("u1", 100L));
        when(drafts.sportOf(1L)).thenReturn(java.util.Optional.empty());
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("p1", 5L));

        poller.pollOnce(draft);

        assertEquals(5L, capturedUpsert().get(0).playerId());
    }

    @Test
    void onTheClockSlotHonoursTheReversalRound() {
        // 12 teams. Plain snake: R1 forward, R2 reverse, R3 forward.
        assertEquals(1, LiveDraftPoller.onTheClockSlot(0, 12, 14, 0));
        assertEquals(12, LiveDraftPoller.onTheClockSlot(12, 12, 14, 0));
        assertEquals(1, LiveDraftPoller.onTheClockSlot(24, 12, 14, 0));

        // reversal_round 3 -- the 2026 Ball Knowers NBA draft. Rounds 1 and 2
        // are untouched; round 3 repeats round 2's direction instead of
        // switching back, so pick 25 belongs to slot 12, not slot 1. Before
        // this parameter existed the live room named the wrong manager on the
        // clock for rounds 3 through 14.
        assertEquals(1, LiveDraftPoller.onTheClockSlot(0, 12, 14, 3));
        assertEquals(12, LiveDraftPoller.onTheClockSlot(12, 12, 14, 3));
        assertEquals(12, LiveDraftPoller.onTheClockSlot(24, 12, 14, 3));
        assertEquals(1, LiveDraftPoller.onTheClockSlot(35, 12, 14, 3));

        // Still null once the board is full, whatever the reversal round.
        assertNull(LiveDraftPoller.onTheClockSlot(168, 12, 14, 3));
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

    /**
     * A draft that never starts must not be polled forever. pollOnce returns
     * keepPolling=true for pre_draft, so opening the live page on a draft
     * scheduled for next month used to leave a thread hitting Sleeper every ten
     * seconds for the life of the process.
     */
    @Test
    void aDraftThatNeverStartsStopsBeingPolled() throws InterruptedException {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players,
                Duration.ofMillis(10), Duration.ofMillis(50));
        DraftRepository.DraftRow draft = draftRow("pre_draft");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(Map.of());

        Thread t = Thread.ofVirtual().start(() -> poller.loop(draft));
        t.join(Duration.ofSeconds(5));
        assertFalse(t.isAlive(), "the loop should have given up on a draft that never started");
        // Never fetched picks: pre_draft skips them, so nothing was ingested for
        // all that polling either.
        verify(sleeper, never()).draftPicks(any());
    }

    /**
     * The other half of the same rule, and the more important one: once the
     * draft is actually underway there is no deadline at all. Some leagues run
     * slow drafts with multi-hour pick clocks, and a poller that quits three
     * hours into a live draft is a far worse failure than a wasted request.
     */
    @Test
    void aDraftThatHasStartedIsNotAbandonedOnTheDeadline() throws InterruptedException {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players,
                Duration.ofMillis(10), Duration.ofMillis(20));
        DraftRepository.DraftRow draft = draftRow("drafting");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "drafting");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of());
        CountDownLatch ticks = new CountDownLatch(5);
        when(sleeper.draftPicks("sleeper-draft-123")).thenAnswer(inv -> {
            ticks.countDown();
            return List.of();
        });

        Thread t = Thread.ofVirtual().start(() -> poller.loop(draft));
        try {
            // Well past a 20ms deadline: if `drafting` were subject to it, the
            // loop would be dead long before the fifth tick.
            assertTrue(ticks.await(10, TimeUnit.SECONDS),
                    "a live draft must keep being polled past the pre-draft deadline");
            assertTrue(t.isAlive());
        } finally {
            t.interrupt();
            t.join(Duration.ofSeconds(5));
        }
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
    void subscribersSeeEveryTickAndUnsubscribeRemovesThem() throws InterruptedException {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("drafting", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "drafting");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));
        when(players.idsBySleeperId(Sport.NFL)).thenReturn(Map.of("p1", 5L));
        when(sleeper.draftPicks("sleeper-draft-123")).thenReturn(List.of(rawPick("p1", "", 7, 7, 1)));

        // Delivery is asynchronous now -- one virtual thread per subscriber, so a
        // viewer whose socket has stalled cannot park the poll thread and stop
        // ingest for everyone. A blocking queue rather than a plain list, so the
        // test waits for the handoff instead of racing it.
        BlockingQueue<LiveDraftPoller.LiveSnapshot> seen = new LinkedBlockingQueue<>();
        Runnable unsubscribe = poller.subscribe(1L, seen::add);
        assertEquals(1, poller.listenerCount(1L));

        poller.pollOnce(draft);
        LiveDraftPoller.LiveSnapshot s = seen.poll(5, TimeUnit.SECONDS);
        assertNotNull(s, "the subscriber must receive the tick");
        assertEquals("drafting", s.status());
        assertEquals(1, s.picksMade());
        assertEquals(7, s.lastPickNo());
        assertEquals(14, s.seatsMapped());
        // 1 pick made at 14 teams -> pick 2 is next, still in round 1, so slot 2.
        assertEquals(2, s.onTheClockSlot());

        unsubscribe.run();
        assertEquals(0, poller.listenerCount(1L));
        poller.pollOnce(draft);
        assertNull(seen.poll(500, TimeUnit.MILLISECONDS),
                "an unsubscribed listener must stop receiving");
    }

    /**
     * The most important defensive line in the live-stream work: a dead browser tab
     * throwing out of its listener must never take down draft-night ingest. The
     * listener is dropped; the tick and every other listener carry on.
     */
    @Test
    void aThrowingListenerIsDroppedAndDoesNotBreakTheTick() throws InterruptedException {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        BlockingQueue<LiveDraftPoller.LiveSnapshot> healthy = new LinkedBlockingQueue<>();
        poller.subscribe(1L, s -> { throw new IllegalStateException("ResponseBodyEmitter has already completed"); });
        poller.subscribe(1L, healthy::add);
        assertEquals(2, poller.listenerCount(1L));

        LiveDraftPoller.Tick tick = assertDoesNotThrow(() -> poller.pollOnce(draft));

        assertTrue(tick.keepPolling(), "the tick must survive a listener blowing up");
        assertNotNull(healthy.poll(5, TimeUnit.SECONDS), "the healthy listener still gets its snapshot");
        // The drop now happens on the dead subscriber's own delivery thread, so
        // it is awaited rather than asserted the instant the tick returns.
        awaitListenerCount(1, "the throwing listener must be dropped");

        poller.pollOnce(draft);
        assertNotNull(healthy.poll(5, TimeUnit.SECONDS));
    }

    private void awaitListenerCount(int expected, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (poller.listenerCount(1L) == expected) return;
            Thread.sleep(10);
        }
        assertEquals(expected, poller.listenerCount(1L), message);
    }

    /**
     * The multi-user failure the delivery decoupling exists for: twelve people
     * watching the same draft, one of whose laptops has gone to sleep with the
     * TCP connection not yet reset. That listener's send blocks rather than
     * throwing, so inline delivery parked the poll thread inside it -- no Sleeper
     * ingest and no updates for the other eleven, for as long as the OS took to
     * give up on the socket. The throwing-listener guard never covered this,
     * because a stalled write does not throw, it waits.
     */
    @Test
    void aBlockedSubscriberDoesNotStallTheTickOrTheOtherSubscribers() throws InterruptedException {
        poller = new LiveDraftPoller(sleeper, drafts, managers, players);
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        CountDownLatch stuckEntered = new CountDownLatch(1);
        CountDownLatch releaseStuck = new CountDownLatch(1);
        BlockingQueue<LiveDraftPoller.LiveSnapshot> healthy = new LinkedBlockingQueue<>();
        poller.subscribe(1L, s -> {
            stuckEntered.countDown();
            try {
                releaseStuck.await();       // the asleep laptop: blocks, never throws
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        poller.subscribe(1L, healthy::add);

        try {
            long start = System.nanoTime();
            poller.pollOnce(draft);
            long tickMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(stuckEntered.await(5, TimeUnit.SECONDS), "the stuck listener should have been called");
            assertTrue(tickMs < 2_000, "the tick must not wait on a blocked subscriber, took " + tickMs + " ms");
            assertNotNull(healthy.poll(5, TimeUnit.SECONDS),
                    "a healthy subscriber must still be served while another is stuck");

            // And ingest keeps running: a further tick neither blocks nor throws.
            assertDoesNotThrow(() -> poller.pollOnce(draft));
            assertNotNull(healthy.poll(5, TimeUnit.SECONDS));
        } finally {
            releaseStuck.countDown();
        }
    }

    /**
     * Twelve browsers opening the live page in the same second. Each one
     * auto-tracks (LeagueController.liveStream), and before the per-draft lock
     * every one of them saw active.containsKey == false and ran its own full
     * synchronous tick -- sleeper.draft + sleeper.draftPicks + a 210-row upsert,
     * twelve times over, aimed at Sleeper at the exact moment everyone arrives.
     */
    @Test
    void concurrentTracksRunExactlyOneTickAndStartExactlyOnePoller() throws InterruptedException {
        // An hour's interval so the spawned poll loop's own first tick cannot
        // land inside the assertions below and turn the Sleeper call count into
        // a race.
        poller = new LiveDraftPoller(sleeper, drafts, managers, players, Duration.ofHours(1));
        DraftRepository.DraftRow draft = draftRow("pre_draft", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "pre_draft");
        raw.put("draft_order", draftOrderOf(14));
        when(sleeper.draft("sleeper-draft-123")).thenReturn(raw);
        when(managers.idsBySleeperUserId()).thenReturn(managersOf(14));

        int callers = 12;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        List<LiveDraftPoller.TrackResult> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    results.add(poller.track(draft));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "every track() call should have returned");

        verify(sleeper, times(1)).draft("sleeper-draft-123");
        assertEquals(callers, results.size());
        assertEquals(1, results.stream().filter(LiveDraftPoller.TrackResult::started).count(),
                "exactly one caller should report having started the poller");
        assertTrue(results.stream().allMatch(LiveDraftPoller.TrackResult::pollerRunning),
                "every caller should be told a poller is running");
        // The losers must answer from the winner's observation rather than from
        // the stale DraftRow -- that honesty is why /track ticks at all.
        assertTrue(results.stream().allMatch(r -> "pre_draft".equals(r.status())));
        assertTrue(results.stream().allMatch(r -> r.seatsMapped() == 14),
                "every caller should see the freshly observed seat count");
        assertTrue(results.stream().allMatch(LiveDraftPoller.TrackResult::observed),
                "no caller should have to fall back to the stored status");
    }
}
