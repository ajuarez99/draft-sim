package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Polls Sleeper for a tracked draft's status and picks on a fixed interval, one
 * virtual thread per draft. Safe to call {@link #track} any time before a draft
 * starts -- it no-ops (status + seat map only, no pick ingest) while status is
 * pre_draft, starts ingesting once it observes drafting, and stops once it
 * observes complete.
 *
 * In-memory only: no persisted tracking state, no reconciliation across a process
 * restart. See claude/live-poller-plan.md decision 2 for why that's fine for a
 * single-instance, single-draft-night deployment.
 */
@Component
public class LiveDraftPoller {

    private static final Logger log = LoggerFactory.getLogger(LiveDraftPoller.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);

    // Consecutive failures stretch the interval to at most 6x (60s). The failure
    // mode this guards is Sleeper rate-limiting us: hammering a 429 produces more
    // 429s, so a run of failures is exactly when polling harder is worst. Capped
    // rather than unbounded because the draft is still moving underneath us and a
    // minute stale is the most we can accept mid-draft.
    private static final int MAX_BACKOFF_MULTIPLIER = 6;

    private final SleeperClient sleeper;
    private final DraftRepository drafts;
    private final ManagerRepository managers;
    private final PlayerRepository players;
    private final Duration pollInterval;

    private final ConcurrentHashMap<Long, Thread> active = new ConcurrentHashMap<>();

    // Last seat map this process derived per draft, used ONLY to decide whether a
    // tick is worth logging -- the write itself is unconditional (see
    // refreshSeatMap). Not seeded from the DraftRow, so the first tick of any
    // draft always logs its seat count once, which is the line worth having on
    // draft night.
    private final ConcurrentHashMap<Long, Map<String, Long>> lastSeatMap = new ConcurrentHashMap<>();

    // What the loop last actually observed from Sleeper, per draft. Exists so a
    // /track call against an already-tracked draft can answer from it instead of
    // firing a second full tick on the request thread -- see track().
    private final ConcurrentHashMap<Long, Tick> lastTick = new ConcurrentHashMap<>();

    // SSE subscribers, per draft. CopyOnWriteArrayList because publish() iterates
    // on the poll thread while subscribe/unsubscribe run on Tomcat request threads
    // and on the listener's own failure path; the snapshot iterator makes removal
    // during iteration safe. Empty lists are deliberately left in the map rather
    // than pruned -- pruning races with a concurrent subscribe, and the map is
    // bounded by the number of drafts ever streamed in one process lifetime.
    private final Map<Long, List<Consumer<LiveSnapshot>>> listeners = new ConcurrentHashMap<>();

    // Explicit: with two constructors Spring will not guess, and the second one
    // exists only as a test seam.
    @Autowired
    public LiveDraftPoller(SleeperClient sleeper, DraftRepository drafts,
                           ManagerRepository managers, PlayerRepository players) {
        this(sleeper, drafts, managers, players, POLL_INTERVAL);
    }

    /**
     * Test seam. The loop's backoff behaviour -- that a throwing tick still sleeps
     * -- is only observable in-process if a test can shrink the 10s interval, and
     * that behaviour is the whole point of the restructure below.
     */
    LiveDraftPoller(SleeperClient sleeper, DraftRepository drafts,
                    ManagerRepository managers, PlayerRepository players, Duration pollInterval) {
        this.sleeper = sleeper;
        this.drafts = drafts;
        this.managers = managers;
        this.players = players;
        this.pollInterval = pollInterval;
    }

    /**
     * @param seatsMapped how many slots the poller could resolve to a manager on this tick
     * @param observed    whether {@code status} came from a live Sleeper read or is a
     *                    fallback to the stale DB value. The whole point of running a
     *                    synchronous tick is "don't report pre_draft for a draft that
     *                    has been live for an hour" -- one Sleeper hiccup reintroduces
     *                    exactly that, so the degradation is labelled rather than silent.
     * @param pollerRunning whether a poll loop is alive for this draft afterwards. Not
     *                    the same as {@code started}: a complete draft deliberately
     *                    spawns nothing, so started=false there means "no poller", not
     *                    "someone else is already polling".
     */
    public record TrackResult(boolean started, String status, int seatsMapped,
                              boolean observed, boolean pollerRunning) {}

    /** What one tick observed. keepPolling false means the draft is over. */
    record Tick(boolean keepPolling, String status, int seatsMapped, boolean observed) {}

    /** What a live-stream subscriber is told after every tick. */
    public record LiveSnapshot(String status, int picksMade, int lastPickNo,
                               int seatsMapped, Integer onTheClockSlot) {}

    /**
     * Idempotent: calling twice for the same draft id starts exactly one poller.
     *
     * Runs one tick synchronously on the calling thread ONLY when nothing is
     * already tracking this draft. /track used to return {@code draft.status()} --
     * the value that happened to be in the DB when the row was read -- which made
     * the endpoint useless as a draft-night diagnostic: it would happily report
     * "pre_draft" for a draft that had been live for an hour. The returned status
     * and seat count are now what Sleeper says right now.
     *
     * The already-tracking short-circuit is not just an optimization. This method
     * used to tick unconditionally, so a second /track ran a second full tick
     * (sleeper.draft + sleeper.draftPicks + a 210-row upsert) on the Tomcat request
     * thread, racing the poll loop's own tick. /track is the only way to read
     * seatsMapped, so an operator refreshing it by hand during the draft doubled
     * our Sleeper load at exactly the wrong moment.
     */
    public TrackResult track(DraftRepository.DraftRow draft) {
        if (active.containsKey(draft.id())) {
            Tick last = lastTick.get(draft.id());
            if (last != null) {
                return new TrackResult(false, last.status(), last.seatsMapped(), last.observed(), true);
            }
            // Tracking started but no tick has completed yet (the very first tick
            // threw). Report the stored row and say so.
            return new TrackResult(false, draft.status(),
                    DraftOrderMapper.normalize(draft.slotToManager()).size(), false, true);
        }

        Tick tick = tickQuietly(draft);
        AtomicBoolean started = new AtomicBoolean(false);
        // A draft that is already complete needs no thread -- the synchronous tick
        // above already ingested its picks.
        if (tick.keepPolling()) {
            active.computeIfAbsent(draft.id(), id -> {
                started.set(true);
                return spawn(draft);
            });
        }
        return new TrackResult(started.get(), tick.status(), tick.seatsMapped(),
                tick.observed(), tick.keepPolling());
    }

    /** A failed first tick must not fail /track -- start polling and let the loop retry. */
    private Tick tickQuietly(DraftRepository.DraftRow draft) {
        try {
            return pollOnce(draft);
        } catch (Exception e) {
            log.warn("synchronous /track tick failed for draft {} -- starting the poller anyway",
                    draft.id(), e);
            // observed=false: this status is the DB's, not Sleeper's, and the caller
            // has to be able to tell the difference.
            return new Tick(true, draft.status(),
                    DraftOrderMapper.normalize(draft.slotToManager()).size(), false);
        }
    }

    /**
     * Registers a live-stream listener and returns its unsubscribe. The listener
     * runs on the poll thread, so it must not block.
     */
    public Runnable subscribe(long draftId, Consumer<LiveSnapshot> listener) {
        List<Consumer<LiveSnapshot>> ls =
                listeners.computeIfAbsent(draftId, id -> new CopyOnWriteArrayList<>());
        ls.add(listener);
        return () -> ls.remove(listener);
    }

    /** Whether a poll loop is alive for this draft. */
    public boolean isTracking(long draftId) {
        return active.containsKey(draftId);
    }

    /** For tests: a leaked listener grows for the whole three hours of a draft. */
    public int listenerCount(long draftId) {
        List<Consumer<LiveSnapshot>> ls = listeners.get(draftId);
        return ls == null ? 0 : ls.size();
    }

    /**
     * Every accept is individually guarded and a throwing listener is dropped.
     *
     * This is the defensive line that matters: a dead browser tab whose SseEmitter
     * throws must never propagate out of the poll loop. Ingest going down for the
     * rest of draft night because a laptop went to sleep is not a trade this is
     * willing to make, so the catch is deliberately Throwable rather than
     * Exception -- there is nothing a listener can throw that is worth stopping
     * ingest for.
     */
    private void publish(long draftId, LiveSnapshot snapshot) {
        List<Consumer<LiveSnapshot>> ls = listeners.get(draftId);
        if (ls == null || ls.isEmpty()) return;
        for (Consumer<LiveSnapshot> listener : ls) {
            try {
                listener.accept(snapshot);
            } catch (Throwable t) {
                ls.remove(listener);
                log.warn("dropped a live-stream listener for draft {} after it threw", draftId, t);
            }
        }
    }

    private Thread spawn(DraftRepository.DraftRow draft) {
        // Waits one interval before its first tick: track() already ran one on the
        // calling thread, and without the delay every /track would fire two full
        // Sleeper fetches back to back.
        return Thread.ofVirtual().name("draft-poll-" + draft.id()).start(() -> {
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                active.remove(draft.id());
                return;
            }
            loop(draft);
        });
    }

    void loop(DraftRepository.DraftRow draft) {
        int consecutiveFailures = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!pollOnce(draft).keepPolling()) break;
                consecutiveFailures = 0;
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("poll tick failed for draft {} ({} in a row)", draft.id(), consecutiveFailures, e);
                // keep looping -- transient (Sleeper hiccup, network blip)
            }
            // The sleep lives OUTSIDE the try that can throw, deliberately. It used
            // to be the last statement inside it, so any exception from pollOnce
            // jumped straight past it and the loop re-entered with no delay at all
            // -- an unthrottled hammer on api.sleeper.app, which trips Sleeper's
            // rate limit, which is itself an exception, which sustains the loop.
            // A self-inflicted outage, on draft night, from one transient 500.
            try {
                Thread.sleep(pollInterval.multipliedBy(
                        Math.min(consecutiveFailures + 1, MAX_BACKOFF_MULTIPLIER)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        active.remove(draft.id());
        lastSeatMap.remove(draft.id());
        // Dropped with the loop that produced it: track() only reads lastTick while
        // the draft is still in `active`, and leaving a stale one behind would let a
        // later /track answer from an observation nothing is refreshing any more.
        lastTick.remove(draft.id());
    }

    /** One Sleeper fetch + (conditionally) one upsert + one status write. No sleep -- unit-testable. */
    Tick pollOnce(DraftRepository.DraftRow draft) {
        Map<String, Object> raw = sleeper.draft(draft.sleeperDraftId());
        String status = raw.get("status") == null ? null : raw.get("status").toString();
        drafts.updateStatus(draft.id(), status);

        // Fetched once per tick and shared by both consumers below: the seat-map
        // refresh and (further down) picked_by resolution. It is a ~14-row table.
        Map<String, Long> managerByUserId = managers.idsBySleeperUserId();
        Map<Integer, Long> slotLookup = refreshSeatMap(draft, raw, managerByUserId);

        if ("pre_draft".equals(status)) {
            return publishTick(draft, new Tick(true, status, slotLookup.size(), true), 0, 0);
        }

        // Fetch picks even on complete, exactly once, before stopping. The last
        // drafting tick lands at T; several more picks can be made and the draft
        // closed before T+10s, at which point returning early on "complete" left
        // those picks permanently missing from draft_pick. upsertPicks is
        // idempotent, so re-ingesting the whole list here costs nothing.
        boolean complete = "complete".equals(status);

        List<Map<String, Object>> rawPicks = sleeper.draftPicks(draft.sleeperDraftId());
        Map<String, Long> playerIdsBySleeperId = players.idsBySleeperId(Sport.NFL);

        List<DraftRepository.PickRow> rows = new ArrayList<>();
        if (rawPicks != null) {
            for (Map<String, Object> p : rawPicks) {
                rows.add(PickMapper.toPickRow(draft.id(), p, managerByUserId, slotLookup, playerIdsBySleeperId));
            }
        }
        drafts.upsertPicks(draft.id(), rows);

        int lastPickNo = 0;
        for (DraftRepository.PickRow r : rows) lastPickNo = Math.max(lastPickNo, r.pickNo());
        return publishTick(draft, new Tick(!complete, status, slotLookup.size(), true),
                rows.size(), lastPickNo);
    }

    /**
     * Remembers the tick and fans it out to live-stream subscribers. Both are the
     * poll loop's only outward-facing side effects beyond the DB, and both are
     * funnelled through here so pollOnce's two exit paths cannot diverge.
     */
    private Tick publishTick(DraftRepository.DraftRow draft, Tick tick, int picksMade, int lastPickNo) {
        lastTick.put(draft.id(), tick);
        publish(draft.id(), new LiveSnapshot(tick.status(), picksMade, lastPickNo,
                tick.seatsMapped(), onTheClockSlot(picksMade, draft.teams(), draft.rounds())));
        return tick;
    }

    /**
     * The slot whose turn it is, or null once every pick is in. Shared with the
     * live-stream endpoint's initial DB-synthesized state so the two agree.
     */
    public static Integer onTheClockSlot(int picksMade, int teams, int rounds) {
        if (teams <= 0 || picksMade >= teams * rounds) return null;
        return DraftSlot.slot(picksMade + 1, teams);
    }

    /**
     * Re-derives slot -> manager from the draft object already in hand and persists
     * it, every tick, pre_draft included.
     *
     * The seat map used to be frozen at /track time. Sleeper returns
     * {@code "draft_order": null} until the commissioner sets the order (verified
     * live against West Coast FF 2026 while it was pre_draft), and
     * LeagueIngestService therefore persisted an EMPTY map for it. With an empty
     * map every autopick -- Sleeper leaves picked_by blank on those -- falls back
     * to a slot lookup that resolves to nothing, the rows land with manager_id
     * null, allCompletedPicks filters them out, and every seat simulates as a
     * league-average bot for the whole draft. The commissioner typically sets the
     * order minutes to hours before the first pick, so pre_draft is exactly when
     * this needs to start working.
     *
     * @return the slot lookup to use for THIS tick's pick mapping -- the caller
     *         must not fall back to {@code draft.slotToManager()}, which is the
     *         immutable row captured once by track() and held for the thread's
     *         whole life. Using it would leave the pick mapping stale on the very
     *         tick that fixed the map.
     */
    private Map<Integer, Long> refreshSeatMap(DraftRepository.DraftRow draft, Map<String, Object> raw,
                                              Map<String, Long> managerByUserId) {
        Map<String, Long> known = lastSeatMap.get(draft.id());
        Map<String, Long> derived = DraftOrderMapper.slotToManager(raw, managerByUserId);

        if (derived.isEmpty()) {
            // draft_order not set yet (or every user in it is unknown to us). Keep
            // whatever we already had rather than persisting an empty map over a
            // good one.
            Map<String, Long> fallback =
                    known != null ? known : DraftOrderMapper.normalize(draft.slotToManager());

            // This early return used to skip the logging block below entirely, so
            // the one case the logging exists to catch -- ZERO seats mapped, every
            // seat simulating as an unattributed league-average bot -- was the only
            // case that said nothing at all, silently, every 10 seconds, forever.
            //
            // Rate-limited through lastSeatMap the same way the block below is:
            // seeding it with the fallback both fires this once on the transition
            // into "no derived map" and keeps the fallback alive for later ticks
            // (a bare Map.of() marker would have thrown away the stored seat map on
            // the very next tick).
            if (known == null) {
                lastSeatMap.put(draft.id(), fallback);
                if (fallback.isEmpty()) {
                    log.error("draft {} draft_order is still null -- 0 of {} seats mapped; every"
                                    + " seat will simulate as a league-average bot until the"
                                    + " commissioner sets the draft order",
                            draft.id(), draft.teams());
                } else {
                    log.warn("draft {} draft_order is null -- falling back to the {} stored seat(s)",
                            draft.id(), fallback.size());
                }
            }
            return DraftOrderMapper.slotLookup(fallback);
        }

        // Written unconditionally: an UPDATE by primary key is free, and the
        // obvious `.equals` change-detector is a trap here -- the stored map comes
        // back from Jackson with Integer values while this one holds Longs, and
        // Integer.valueOf(5).equals(5L) is false, so it would fire every 10s
        // forever. Only the logging is gated, on a like-for-like comparison.
        drafts.updateSlotToManager(draft.id(), JsonUtil.write(derived));

        if (!derived.equals(known)) {
            lastSeatMap.put(draft.id(), derived);
            List<String> unmapped = DraftOrderMapper.unmappedUserIds(raw, managerByUserId);
            if (!unmapped.isEmpty()) {
                log.warn("draft {} draft_order carries {} sleeper user(s) with no manager row: {}"
                                + " -- re-run the league ingest",
                        draft.id(), unmapped.size(), unmapped);
            }
            if (derived.size() != draft.teams()) {
                log.error("draft {} slot map INCOMPLETE -- {} of {} seats mapped; the rest will"
                                + " simulate as league-average bots",
                        draft.id(), derived.size(), draft.teams());
            } else {
                log.info("draft {} slot map refreshed from draft_order -- all {} seats mapped",
                        draft.id(), derived.size());
            }
        }
        return DraftOrderMapper.slotLookup(derived);
    }

    /** So a bootRun restart doesn't leave dangling pollers still hitting Sleeper after shutdown began. */
    @PreDestroy
    public void shutdown() {
        active.values().forEach(Thread::interrupt);
    }
}
