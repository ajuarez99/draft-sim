package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.DraftOrderMapper;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api")
public class LeagueController {

    private static final Logger log = LoggerFactory.getLogger(LeagueController.class);

    // Four hours, not 0 (= never). A real draft runs ~3; an unbounded emitter that
    // outlives the draft is a leak with no upper bound, and EventSource reconnects
    // on its own if the timeout ever fires mid-draft.
    private static final long LIVE_SSE_TIMEOUT_MS = 4L * 60 * 60 * 1000L;
    private static final long LIVE_HEARTBEAT_MS = 15_000L;

    private final LeagueRepository leagues;
    private final DraftRepository drafts;
    private final ProfileService profiles;
    private final BoardService boards;
    private final LiveDraftPoller poller;
    private final ManagerRepository managers;
    private final PlayerRepository players;
    private final OwnerProperties owner;

    public LeagueController(LeagueRepository leagues, DraftRepository drafts,
                            ProfileService profiles, BoardService boards, LiveDraftPoller poller,
                            ManagerRepository managers, PlayerRepository players,
                            OwnerProperties owner) {
        this.leagues = leagues;
        this.drafts = drafts;
        this.profiles = profiles;
        this.boards = boards;
        this.poller = poller;
        this.managers = managers;
        this.players = players;
        this.owner = owner;
    }

    @GetMapping("/leagues")
    public List<LeagueRepository.LeagueRow> leagues() {
        return leagues.all();
    }

    /** Every draft in the DB, newest first. Backs the app-shell picker screen. */
    @GetMapping("/drafts")
    public List<DraftRepository.DraftSummary> drafts() {
        return drafts.allWithLeague();
    }

    /** Seats with their profiles. draftsObserved is here so the UI can be honest. */
    @GetMapping("/drafts/{sleeperDraftId}/seats")
    public ResponseEntity<?> seats(@PathVariable String sleeperDraftId) {
        Optional<DraftRepository.DraftRow> draft = drafts.bySleeperId(sleeperDraftId);
        if (draft.isEmpty()) return ResponseEntity.notFound().build();

        ProfileService.Fit fit = profiles.fit(Sport.NFL);
        List<Map<String, Object>> seats = new ArrayList<>();

        // Reverse-keyed lookup (sleeperUserId -> managerId) already exists for
        // LiveDraftPoller; reused here rather than adding a new forward-keyed
        // ManagerRepository method just to compare against each seat's managerId.
        // A blank/unset config value is the local-dev default and is guarded
        // explicitly rather than relying on a lookup miss to behave correctly.
        Long ownerManagerId = owner.configured()
                ? managers.idsBySleeperUserId().get(owner.sleeperUserId())
                : null;
        Integer[] mySlotHolder = new Integer[1]; // effectively-final box for the lambda below

        draft.get().slotToManager().forEach((slot, managerId) -> {
            long id = ((Number) managerId).longValue();
            if (ownerManagerId != null && ownerManagerId == id) {
                mySlotHolder[0] = Integer.parseInt(slot);
            }
            ManagerProfile p = fit.profiles().getOrDefault(id, ManagerProfile.neutral(id, "seat " + slot));
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("slot", Integer.parseInt(slot));
            seat.put("managerId", p.managerId());
            seat.put("manager", p.displayName());
            seat.put("provenance", p.provenance().name());
            seat.put("reachBias", round2(p.reachBias()));
            seat.put("unpredictability", p.unpredictability());
            seat.put("positionalTilt", p.positionalTilt());
            seat.put("note", p.note());
            seat.put("draftsObserved", p.draftsObserved());
            seat.put("picksScored", p.picksScored());
            seats.add(seat);
        });
        seats.sort(Comparator.comparingInt(s -> (Integer) s.get("slot")));

        // rosterPositions is always a non-null List (roster_positions is `text[]
        // not null default '{}'`), including legitimately empty when a league's
        // roster settings haven't synced -- the frontend team-needs helper treats
        // [] as "hide the strip", not an error.
        List<String> rosterPositions = leagues.byId(draft.get().leagueId())
                .map(LeagueRepository.LeagueRow::rosterPositions)
                .orElseGet(List::of);

        // Map.of rejects null values, and mySlot is null in the default case --
        // unset config, or a configured owner who isn't a manager in this
        // particular league -- i.e. the state every fresh checkout starts in.
        // LinkedHashMap tolerates the null directly, same fix board() below
        // already applies for its own nullable field.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftId", sleeperDraftId);
        response.put("teams", draft.get().teams());
        response.put("rounds", draft.get().rounds());
        // The real value, nullable. String.valueOf() here produced the literal
        // four-character string "null" for a draft whose status column is null,
        // which is valid JSON and indistinguishable from a real status to the
        // frontend -- claude/lessons.md #12, in the one place the fix hadn't
        // landed. The LinkedHashMap above already tolerates a null value, so the
        // workaround wasn't even buying anything.
        response.put("status", draft.get().status());
        response.put("seats", seats);
        response.put("mySlot", mySlotHolder[0]);
        response.put("rosterPositions", rosterPositions);
        return ResponseEntity.ok(response);
    }

    /** Starts (or confirms) live polling for a draft. Safe to call any time before it goes live. */
    @PostMapping("/drafts/{sleeperDraftId}/track")
    public ResponseEntity<?> track(@PathVariable String sleeperDraftId) {
        Optional<DraftRepository.DraftRow> draft = drafts.bySleeperId(sleeperDraftId);
        if (draft.isEmpty()) return ResponseEntity.notFound().build();
        LiveDraftPoller.TrackResult r = poller.track(draft.get());
        // Map.of throws NullPointerException on a null value, and status is
        // genuinely nullable (the column is, and track() now reports what Sleeper
        // returned on a live tick rather than the stale DB value). An NPE isn't
        // handled by ErrorHandler either, so this endpoint -- the draft-night
        // diagnostic -- would have come back as a bare 500.
        // LinkedHashMap tolerates the null, same fix as seats()/board().
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftId", sleeperDraftId);
        // Both of these used to be hardcoded optimism. track() deliberately spawns
        // nothing for a `complete` draft, so started=false there -- and the
        // response said "tracking": true, "alreadyTracking": true, neither of which
        // was so, on the one endpoint whose job is telling you what is actually
        // happening.
        response.put("tracking", r.pollerRunning());
        response.put("alreadyTracking", !r.started() && r.pollerRunning());
        response.put("status", r.status());
        // False means status is the stored DB value, not something Sleeper just
        // told us -- i.e. the synchronous tick threw. Without this a Sleeper hiccup
        // silently reintroduces the exact bug the synchronous tick was added to
        // fix: "pre_draft" reported for a draft that has been live for an hour.
        response.put("observed", r.observed());
        // How many of this draft's seats the poller could resolve to a real
        // manager. Zero means every seat is a league-average bot -- the failure
        // this endpoint most needs to be able to report before 8:15 PM.
        response.put("seatsMapped", r.seatsMapped());
        response.put("teams", draft.get().teams());
        return ResponseEntity.ok(response);
    }

    /**
     * Server-sent draft state for the live page. GET rather than POST so the
     * browser's native EventSource can drive it, which buys automatic reconnect
     * across a three-hour draft for free.
     *
     * Driven entirely off {@link LiveDraftPoller}'s existing poll loop via a
     * listener registry -- deliberately NOT a second polling loop, which would
     * double the load on api.sleeper.app per open browser tab.
     *
     * Events: `state` (immediately on connect from the DB, then on every tick where
     * status / picksMade / seatsMapped changed), `heartbeat` every 15s regardless
     * so the UI can render "last contact 4s ago" and a silently-dead poller is
     * visible, and `error`.
     */
    @GetMapping(value = "/drafts/{sleeperDraftId}/live-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> liveStream(@PathVariable String sleeperDraftId) {
        Optional<DraftRepository.DraftRow> found = drafts.bySleeperId(sleeperDraftId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();   // matches seats()
        DraftRepository.DraftRow draft = found.get();

        // Auto-track on open. The failure that cannot happen tonight is "I opened
        // the live page, it looked fine, and nothing was polling." Skipped for a
        // complete draft on purpose: track() would run a full synchronous tick,
        // this method then completes the emitter, EventSource reconnects a few
        // seconds later, and the pair would loop as a slow hammer on Sleeper.
        if (!poller.isTracking(draft.id()) && !"complete".equals(draft.status())) {
            try {
                poller.track(draft);
            } catch (Exception e) {
                // Never let a tracking failure block the stream -- a UI that paints
                // with tracking:false is far better than one that fails to open.
                log.warn("live-stream auto-track failed for draft {}", sleeperDraftId, e);
            }
        }

        SseEmitter emitter = new SseEmitter(LIVE_SSE_TIMEOUT_MS);

        // Synthesized from the DB so the page paints now rather than waiting up to
        // a full poll interval for the next tick.
        List<DraftRepository.PickRow> stored = drafts.picks(draft.id());
        int picksMade = stored.size();
        int lastPickNo = stored.stream().mapToInt(DraftRepository.PickRow::pickNo).max().orElse(0);
        LiveDraftPoller.LiveSnapshot initial = new LiveDraftPoller.LiveSnapshot(
                draft.status(), picksMade, lastPickNo, draft.slotToManager().size(),
                LiveDraftPoller.onTheClockSlot(picksMade, draft.teams(), draft.rounds()));

        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicReference<String> lastKey = new AtomicReference<>(changeKey(initial));
        Runnable[] unsubscribe = new Runnable[1];
        Runnable cleanup = () -> {
            alive.set(false);
            if (unsubscribe[0] != null) unsubscribe[0].run();
        };
        // Unsubscribe explicitly rather than leaving it to onCompletion. Spring only
        // wires the completion callback once the async response is initialized, so
        // relying on it alone would leave a listener attached in any path that
        // closes the stream before that -- and a listener leaked at 8:15 PM is one
        // the poll loop keeps calling for the next three hours. Both are idempotent.
        Runnable finish = () -> {
            cleanup.run();
            emitter.complete();
        };

        try {
            send(emitter, "state", statePayload(sleeperDraftId, draft, initial));
        } catch (Exception e) {
            // The client hung up between the request and the first write.
            emitter.completeWithError(e);
            return ResponseEntity.ok(emitter);
        }

        // Subscribed AFTER the initial state so a tick landing mid-setup cannot be
        // written ahead of it and leave the UI painting a stale board over a fresh one.
        unsubscribe[0] = poller.subscribe(draft.id(), snapshot -> {
            String key = changeKey(snapshot);
            if (!key.equals(lastKey.getAndSet(key))) {
                // Throws on failure, which is the contract: LiveDraftPoller.publish
                // drops any listener that throws, so a dead tab unsubscribes itself.
                send(emitter, "state", statePayload(sleeperDraftId, draft, snapshot));
            }
            if ("complete".equals(snapshot.status())) finish.run();
        });

        emitter.onCompletion(cleanup);
        emitter.onTimeout(finish);
        emitter.onError(e -> cleanup.run());

        // A named heartbeat event, not an SSE comment, so the UI can show real
        // staleness. It is also the only thing that detects a dead client:
        // SseEmitter only discovers a broken pipe on its next send, and a pre_draft
        // draft may go an hour without a state event.
        Thread.ofVirtual().name("live-hb-" + draft.id()).start(() -> {
            while (alive.get()) {
                try {
                    Thread.sleep(LIVE_HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!alive.get()) break;
                try {
                    send(emitter, "heartbeat", Map.of("serverTime", nowIso()));
                } catch (Exception e) {
                    // Broken pipe, or an emitter already completed by the listener
                    // above. Either way this stream is over.
                    cleanup.run();
                    break;
                }
            }
        });

        // A complete draft gets its one state above and nothing more.
        if ("complete".equals(draft.status())) finish.run();

        return ResponseEntity.ok(emitter);
    }

    /**
     * Records a pick by hand into real {@code draft_pick} -- the escape hatch for
     * when the poller lags a pick that is already visible in Sleeper's own UI.
     *
     * A DB write rather than an in-memory override because every consumer (sim
     * resume-from-state, seats, the board) already reads the DB, and because it
     * self-heals: the poller re-upserts the full pick list every tick, so this row
     * is overwritten with the truth as soon as Sleeper catches up.
     *
     * Safety: this writes into real {@code draft_pick} for a live, `drafting`
     * draft. It cannot contaminate fitted manager profiles while the draft is
     * running, because {@code DraftRepository.allCompletedPicks} filters on
     * {@code d.status = 'complete'} -- pinned by
     * {@code DraftRepositoryUpsertPicksIT.allCompletedPicksExcludesPicksFromANonCompleteDraft}.
     */
    @PostMapping("/drafts/{sleeperDraftId}/picks")
    public ResponseEntity<?> recordPick(@PathVariable String sleeperDraftId,
                                        @RequestBody(required = false) ManualPick body) {
        Optional<DraftRepository.DraftRow> found = drafts.bySleeperId(sleeperDraftId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        DraftRepository.DraftRow draft = found.get();

        int totalPicks = draft.teams() * draft.rounds();
        if (body == null || body.pickNo() == null || body.pickNo() < 1 || body.pickNo() > totalPicks) {
            return badRequest("pickNo must be between 1 and " + totalPicks);
        }
        if (body.sleeperPlayerId() == null || body.sleeperPlayerId().isBlank()) {
            return badRequest("sleeperPlayerId is required");
        }
        // 400 rather than writing player_id null. A silent null here would look
        // like a successful pick in the UI while producing a pick row the engine
        // and the board both ignore.
        Long playerId = players.idsBySleeperId(Sport.NFL).get(body.sleeperPlayerId());
        if (playerId == null) {
            return badRequest("unknown sleeperPlayerId: " + body.sleeperPlayerId()
                    + " -- re-run POST /api/ingest/players if this is a new player");
        }

        int pickNo = body.pickNo();
        int round = DraftSlot.round(pickNo, draft.teams());
        int slot = DraftSlot.slot(pickNo, draft.teams());
        Long managerId = DraftOrderMapper.normalize(draft.slotToManager()).get(String.valueOf(slot));

        drafts.upsertPicks(draft.id(), List.of(new DraftRepository.PickRow(
                draft.id(), pickNo, round, slot, managerId, playerId, null)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftId", sleeperDraftId);
        response.put("pickNo", pickNo);
        response.put("round", round);
        response.put("draftSlot", slot);
        // Nullable, and honestly so: an unset draft_order means this pick lands
        // unattributed, exactly like an autopick would.
        response.put("managerId", managerId);
        response.put("playerId", playerId);
        return ResponseEntity.ok(response);
    }

    /** Body of POST /api/drafts/{id}/picks. */
    public record ManualPick(Integer pickNo, String sleeperPlayerId) {}

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /** The three fields a `state` event exists to report a change in. */
    private static String changeKey(LiveDraftPoller.LiveSnapshot s) {
        return s.status() + "|" + s.picksMade() + "|" + s.seatsMapped();
    }

    private Map<String, Object> statePayload(String sleeperDraftId, DraftRepository.DraftRow draft,
                                             LiveDraftPoller.LiveSnapshot s) {
        // LinkedHashMap, not Map.of: status and onTheClockSlot are both legitimately
        // null (a null status column; a finished draft).
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("draftId", sleeperDraftId);
        m.put("status", s.status());
        m.put("tracking", poller.isTracking(draft.id()));
        m.put("picksMade", s.picksMade());
        m.put("lastPickNo", s.lastPickNo());
        m.put("totalPicks", draft.teams() * draft.rounds());
        m.put("teams", draft.teams());
        m.put("rounds", draft.rounds());
        m.put("seatsMapped", s.seatsMapped());
        m.put("onTheClockSlot", s.onTheClockSlot());
        m.put("serverTime", nowIso());
        return m;
    }

    private static String nowIso() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Catches Exception, not IOException. {@code SseEmitter.send} after the emitter
     * has completed throws IllegalStateException, which is not an IOException -- so
     * a narrower catch escapes, kills the sending thread, and never runs the
     * cleanup. Rethrown as unchecked so a listener failure is visible to
     * {@code LiveDraftPoller.publish}, which drops the listener.
     */
    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new IllegalStateException("live-stream " + event + " send failed", e);
        }
    }

    /** What the engine is valuing against, so it can be eyeballed before trusting a sim. */
    @GetMapping("/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "60") int limit) {
        var entries = boards.currentBoard(Sport.NFL).stream()
                .limit(limit)
                .map(e -> {
                    // Map.of rejects null values, and a free agent / retired player can have
                    // a null team — String.valueOf(null) used to paper over that by producing
                    // the literal string "null", which a client can't tell apart from a real
                    // team code. LinkedHashMap tolerates the null directly.
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("adp", e.adp());
                    row.put("name", e.player().name());
                    row.put("position", e.position().name());
                    row.put("team", e.player().team());
                    row.put("positionalRank", e.positionalRank());
                    return row;
                })
                .toList();
        return Map.of(
                "capturedOn", boards.currentBoardDate(Sport.NFL).map(Object::toString).orElse("none"),
                "picksWithContemporaneousBoard", boards.picksWithAdpAtTime(),
                "entries", entries);
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
