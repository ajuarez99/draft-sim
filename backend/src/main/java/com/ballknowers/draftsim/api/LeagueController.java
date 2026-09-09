package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.DraftOrderMapper;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.engine.OwnerSlot;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
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

    // How many landed picks ride along on every `state` frame. Twelve, not the
    // three the feed renders: the frontend merges these over the simulation's
    // own landed prefix (which is a *projection* at any pick the last sim
    // didn't know about yet), so this window is really "how far the poller may
    // run ahead of a resim before a projected cell gets shown as a fact". A
    // Sleeper autopick burst empties several seats in one tick, and a resim
    // takes seconds; twelve covers that with room to spare and costs a dozen
    // small objects per changed tick.
    private static final int LIVE_RECENT_PICKS = 12;

    private final LeagueRepository leagues;
    private final DraftRepository drafts;
    private final ProfileService profiles;
    private final BoardService boards;
    private final LiveDraftPoller poller;
    private final ManagerRepository managers;
    private final PlayerRepository players;
    private final OwnerProperties owner;
    private final LeagueMembership membership;

    public LeagueController(LeagueRepository leagues, DraftRepository drafts,
                            ProfileService profiles, BoardService boards, LiveDraftPoller poller,
                            ManagerRepository managers, PlayerRepository players,
                            OwnerProperties owner, LeagueMembership membership) {
        this.leagues = leagues;
        this.drafts = drafts;
        this.profiles = profiles;
        this.boards = boards;
        this.poller = poller;
        this.managers = managers;
        this.players = players;
        this.owner = owner;
        this.membership = membership;
    }

    /**
     * The draft behind this id, if this caller may see it.
     *
     * Empty covers both "no such draft" and "not your league", and every caller
     * turns it into the same 404. Deliberately not a 403: a distinct "forbidden"
     * confirms the draft exists and which league it belongs to, which is most of
     * what an enumeration wanted in the first place.
     *
     * Every draft-addressed route below goes through here rather than calling
     * {@code drafts.bySleeperId} directly, so adding a route without scoping it
     * is a visible omission instead of a silent default.
     */
    private Optional<DraftRepository.DraftRow> visibleDraft(String sleeperDraftId, String sleeperUserId) {
        Optional<DraftRepository.DraftRow> found = drafts.bySleeperId(sleeperDraftId);
        if (found.isEmpty()) return found;
        return membership.canSee(sleeperUserId, found.get().leagueId()) ? found : Optional.empty();
    }

    /**
     * Every draft in the DB, newest first, scoped to the requesting Sleeper
     * user's own leagues when {@code X-Sleeper-User} is sent
     * (claude/user-identity-and-onboarding.md §4b/§4c). Absent header ⇒ the
     * unfiltered list, unchanged from before this header existed.
     */
    @GetMapping("/drafts")
    public List<DraftRepository.DraftSummary> drafts(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        return sleeperUserId == null || sleeperUserId.isBlank()
                ? drafts.allWithLeague()
                : drafts.allWithLeagueFor(sleeperUserId);
    }

    /** Seats with their profiles. draftsObserved is here so the UI can be honest. */
    @GetMapping("/drafts/{sleeperDraftId}/seats")
    public ResponseEntity<?> seats(@PathVariable String sleeperDraftId,
                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> draft = visibleDraft(sleeperDraftId, sleeperUserId);
        if (draft.isEmpty()) return ResponseEntity.notFound().build();

        // Loaded once and reused below for rosterPositions too, rather than the
        // original two separate leagues.byId(...) calls -- this one now also
        // supplies the sport that fit() needs, in place of the hardcoded NFL.
        Optional<LeagueRepository.LeagueRow> league = leagues.byId(draft.get().leagueId());
        Sport sport = league.map(LeagueRepository.LeagueRow::sport).orElse(Sport.NFL);

        ProfileService.Fit fit = profiles.fit(sport);
        List<Map<String, Object>> seats = new ArrayList<>();

        // Extracted to OwnerSlot.resolve so the mock room's "fork a live draft"
        // path can resolve the same default seat without a second copy of this
        // lookup. A blank/unset config value is the local-dev default, handled
        // there rather than relying on a lookup miss to behave correctly.
        Integer mySlot = OwnerSlot.resolve(draft.get(), managers, owner, sleeperUserId);

        draft.get().slotToManager().forEach((slot, managerId) -> {
            long id = ((Number) managerId).longValue();
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
        List<String> rosterPositions = league
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
        response.put("mySlot", mySlot);
        response.put("rosterPositions", rosterPositions);
        // Added for multi-sport-and-rebrand.md Phase 6: the frontend's position
        // lists, slot-eligibility model, and position-run detector all need to
        // know which sport they're rendering rather than assuming football.
        // `sport` above is already resolved (league lookup, defaulting to NFL)
        // for fit(); this just also puts it on the wire. Sport's @JsonValue
        // serializes it as the same lowercase code DraftSummary already uses.
        response.put("sport", sport);
        // Phase 6b. The round from which snake parity flips is the one piece of
        // this project's behaviour that ships as an assumption -- no completed
        // draft in reach uses a nonzero reversal_round, so the semantics were
        // never executed against real data. Putting both numbers on the seats
        // response (which DraftView already fetches) is what lets the settings
        // popover show the assumption and let whoever is running the draft
        // disagree with it. Both are always present; they differ only when
        // someone has overridden.
        DraftRepository.ReversalRound reversal = drafts.reversalRound(draft.get().id())
                .orElse(new DraftRepository.ReversalRound(draft.get().reversalRound(), null));
        response.put("reversalRound", reversal.effective());
        response.put("reversalRoundFromSleeper", reversal.fromSleeper());
        response.put("reversalRoundOverridden", reversal.override() != null);
        return ResponseEntity.ok(response);
    }

    /**
     * Sets (or clears) this draft's reversal-round override.
     *
     * Why an override column and not an edit of {@code reversal_round} itself:
     * {@link DraftRepository#upsert} re-reads Sleeper's value on every ingest
     * and its {@code on conflict} clause writes it back, so an in-place edit
     * would be reverted by one press of the picker's "Add a draft" button --
     * the same shape of bug that ate {@code adp_at_time}.
     *
     * A null (or omitted) {@code reversalRound} clears the override and goes
     * back to following Sleeper. That is a distinct outcome from setting it to
     * Sleeper's current value, and deliberately so.
     */
    @PutMapping("/drafts/{sleeperDraftId}/reversal-round")
    public ResponseEntity<?> setReversalRound(@PathVariable String sleeperDraftId,
                                              @RequestBody(required = false) ReversalRoundBody body,
                                              @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> found = visibleDraft(sleeperDraftId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        DraftRepository.DraftRow draft = found.get();

        Integer override = body == null ? null : body.reversalRound();
        // Upper bound is `rounds`, not unbounded: a value past the last round
        // is inert rather than wrong, and silently accepting a typo that does
        // nothing is worse than refusing it. 0 is valid and means "never".
        if (override != null && (override < 0 || override > draft.rounds())) {
            return badRequest("reversalRound must be between 0 and " + draft.rounds()
                    + " (0 means the draft never reverses), or null to follow Sleeper");
        }
        drafts.setReversalRoundOverride(draft.id(), override);

        DraftRepository.ReversalRound after = drafts.reversalRound(draft.id())
                .orElse(new DraftRepository.ReversalRound(draft.reversalRound(), override));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftId", sleeperDraftId);
        response.put("reversalRound", after.effective());
        response.put("reversalRoundFromSleeper", after.fromSleeper());
        response.put("reversalRoundOverridden", after.override() != null);
        return ResponseEntity.ok(response);
    }

    /** Body of PUT /api/drafts/{id}/reversal-round. Null field = clear the override. */
    public record ReversalRoundBody(Integer reversalRound) {}

    /**
     * The picks that actually happened in this draft, joined to player and manager
     * identity -- as opposed to {@code /api/sims}, which always predicts what
     * WOULD happen and never reads {@code draft_pick} at all. Backs the picker's
     * "Draft board" link for a completed league (claude/board-first-layout-and-
     * pick-latency.md never covered this case; DraftView's simulator was standing
     * in for it, which meant "Draft board" on a finished draft opened an empty
     * room asking you to start a mock rather than showing what the room did).
     *
     * Works for a draft of any status, not just `complete` -- a `drafting` draft
     * simply comes back with however many picks {@link DraftRepository#picks}
     * currently has (the live poller keeps that current), and the caller decides
     * whether that's the view it wants.
     */
    @GetMapping("/drafts/{sleeperDraftId}/board")
    public ResponseEntity<?> realBoard(@PathVariable String sleeperDraftId,
                                       @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> found = visibleDraft(sleeperDraftId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        DraftRepository.DraftRow draft = found.get();
        Sport sport = leagues.byId(draft.leagueId()).map(LeagueRepository.LeagueRow::sport).orElse(Sport.NFL);

        List<Map<String, Object>> picks = pickNaming(sport).rows(drafts.picks(draft.id()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftId", sleeperDraftId);
        response.put("teams", draft.teams());
        response.put("rounds", draft.rounds());
        response.put("status", draft.status());
        response.put("picks", picks);
        return ResponseEntity.ok(response);
    }

    /**
     * A player who has fallen off the current board entirely still needs to
     * render as something rather than vanishing from a real, already-completed
     * pick. Player.primary() is the same "default to WR rather than nothing"
     * convention BoardEntry.position() already relies on; 999 is BoardService's
     * own "no rank" sentinel, which the frontend already special-cases.
     */
    private static SimulationResult.PlayerRef fallbackPlayerRef(Player p) {
        if (p == null) return null;
        return new SimulationResult.PlayerRef(p.id(), p.sleeperId(), p.name(),
                p.primary().name(), p.team(), 999, 999);
    }

    /**
     * Everything needed to turn stored {@code draft_pick} rows into the
     * render-ready shape the frontend's {@code RealPick} mirrors -- the board
     * for adp/positionalRank, the player table for anyone who has fallen off
     * it, and the manager names.
     *
     * Built once and reused, because the two callers have opposite cost
     * profiles: {@code realBoard} resolves a whole draft in one request, while
     * the live stream resolves a handful of picks on every changed tick for
     * hours. Rebuilding the board and player maps per tick would re-read the
     * entire player pool every ten seconds per open tab; none of it changes
     * mid-draft.
     */
    private record PickNaming(Map<Long, BoardEntry> board, Map<Long, Player> players,
                              Map<Long, String> managerNames) {

        /** Null for a pick with no resolvable player -- an unfilled slot, or a player row that is gone. */
        Map<String, Object> row(DraftRepository.PickRow p) {
            if (p.playerId() == null) return null; // pick slot with no resolved player -- nothing to show yet
            BoardEntry entry = board.get(p.playerId());
            SimulationResult.PlayerRef player = entry != null
                    ? SimulationResult.PlayerRef.from(entry)
                    : fallbackPlayerRef(players.get(p.playerId()));
            if (player == null) return null; // player row itself is gone; nothing left to render

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pickNo", p.pickNo());
            row.put("round", p.round());
            row.put("slot", p.draftSlot());
            row.put("manager", p.managerId() != null
                    ? managerNames.getOrDefault(p.managerId(), "Slot " + p.draftSlot())
                    : "Slot " + p.draftSlot());
            row.put("player", player);
            return row;
        }

        List<Map<String, Object>> rows(List<DraftRepository.PickRow> picks) {
            List<Map<String, Object>> out = new ArrayList<>(picks.size());
            for (DraftRepository.PickRow p : picks) {
                Map<String, Object> row = row(p);
                if (row != null) out.add(row);
            }
            return out;
        }

        /**
         * The last {@code limit} picks, oldest first -- the order PickFeed and
         * its position-run detector both read in.
         */
        List<Map<String, Object>> tail(List<DraftRepository.PickRow> picks, int limit) {
            return rows(picks.size() > limit ? picks.subList(picks.size() - limit, picks.size()) : picks);
        }
    }

    /**
     * Real picks reference whatever player was on Sleeper's board the day they
     * were taken -- possibly a player long gone from today's board (retired,
     * dropped from the pool). currentBoard() gives adp/positionalRank for
     * anyone still on it; fallbackPlayerRef covers anyone who isn't, so a pick
     * never silently disappears just because the player is no longer relevant.
     */
    PickNaming pickNaming(Sport sport) {   // package-private: LeagueControllerLiveStreamTest builds one
        Map<Long, BoardEntry> byPlayerId = new HashMap<>();
        for (BoardEntry e : boards.currentBoard(sport)) byPlayerId.put(e.player().id(), e);
        Map<Long, Player> playersById = new HashMap<>();
        for (Player p : players.findAll(sport)) playersById.put(p.id(), p);
        return new PickNaming(byPlayerId, playersById, managers.names());
    }

    /** Starts (or confirms) live polling for a draft. Safe to call any time before it goes live. */
    @PostMapping("/drafts/{sleeperDraftId}/track")
    public ResponseEntity<?> track(@PathVariable String sleeperDraftId,
                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> draft = visibleDraft(sleeperDraftId, sleeperUserId);
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
     *
     * <p>Identity arrives as the {@code user} query parameter here, not the
     * {@code X-Sleeper-User} header every other route uses, because the browser's
     * native EventSource cannot set a request header at all -- the same
     * limitation DEPLOY.md already records for the bearer token. Scoping this
     * route off a header it can never receive would have meant either breaking
     * live mode for everyone or leaving the one endpoint that streams a league's
     * board wide open. Only the transport differs: it feeds the same
     * {@link LeagueMembership#canSee} as everything else, so there is still one
     * definition of whose league is whose. A Sleeper user id is public and
     * unverified either way, so putting it in a URL concedes nothing that
     * sending it in a header did not.
     */
    @GetMapping(value = "/drafts/{sleeperDraftId}/live-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> liveStream(@PathVariable String sleeperDraftId,
                                                 @RequestParam(name = "user", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> found = visibleDraft(sleeperDraftId, sleeperUserId);
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

        // Once, for the life of this stream -- see PickNaming. The sport lookup
        // takes seats()'s NFL fallback for the same reason it does there: a
        // draft whose league row is missing has nothing better to assume.
        Sport sport = leagues.byId(draft.leagueId()).map(LeagueRepository.LeagueRow::sport).orElse(Sport.NFL);
        PickNaming namer = pickNaming(sport);

        // Synthesized from the DB so the page paints now rather than waiting up to
        // a full poll interval for the next tick.
        List<DraftRepository.PickRow> stored = drafts.picks(draft.id());
        int picksMade = stored.size();
        int lastPickNo = stored.stream().mapToInt(DraftRepository.PickRow::pickNo).max().orElse(0);
        LiveDraftPoller.LiveSnapshot initial = new LiveDraftPoller.LiveSnapshot(
                draft.status(), picksMade, lastPickNo, draft.slotToManager().size(),
                LiveDraftPoller.onTheClockSlot(picksMade, draft.teams(), draft.rounds(), draft.reversalRound()));

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
            send(emitter, "state", statePayload(sleeperDraftId, draft, initial, stored, namer));
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
                // Re-read rather than tracking the picks incrementally: the poller
                // upserts the *whole* pick list every tick and the manual-pick
                // endpoint writes into the same table, so the DB is the only place
                // that knows the current truth. One indexed read per changed tick.
                // Throws on failure, which is the contract: LiveDraftPoller.publish
                // drops any listener that throws, so a dead tab unsubscribes itself.
                send(emitter, "state",
                        statePayload(sleeperDraftId, draft, snapshot, drafts.picks(draft.id()), namer));
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
                                        @RequestBody(required = false) ManualPick body,
                                        @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> found = visibleDraft(sleeperDraftId, sleeperUserId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        DraftRepository.DraftRow draft = found.get();

        int totalPicks = draft.teams() * draft.rounds();
        if (body == null || body.pickNo() == null || body.pickNo() < 1 || body.pickNo() > totalPicks) {
            return badRequest("pickNo must be between 1 and " + totalPicks);
        }
        if (body.sleeperPlayerId() == null || body.sleeperPlayerId().isBlank()) {
            return badRequest("sleeperPlayerId is required");
        }
        Sport sport = leagues.byId(draft.leagueId()).map(LeagueRepository.LeagueRow::sport).orElse(Sport.NFL);

        // 400 rather than writing player_id null. A silent null here would look
        // like a successful pick in the UI while producing a pick row the engine
        // and the board both ignore.
        Long playerId = players.idsBySleeperId(sport).get(body.sleeperPlayerId());
        if (playerId == null) {
            return badRequest("unknown sleeperPlayerId: " + body.sleeperPlayerId()
                    + " -- re-run POST /api/ingest/players if this is a new player");
        }

        int pickNo = body.pickNo();
        int round = DraftSlot.round(pickNo, draft.teams());
        // draft.reversalRound() -- this is a real, persisted draft, and a
        // manually-recorded pick needs the same slot math the simulator and
        // the rest of the ingest pipeline would use for it (multi-sport-and-
        // rebrand.md Phase 5/6). 0 for every NFL draft, unchanged from before.
        int slot = DraftSlot.slot(pickNo, draft.teams(), draft.reversalRound());

        // Only the seat's own owner may fill it in. This endpoint writes into
        // real draft_pick, and every league member could previously write any
        // pick number -- so two people hand-recording the same pick differently
        // was a silent last-writer-wins on a live board, with nobody told. The
        // rule is the seat, not the clock: your own missed pick from two rounds
        // ago is still yours to backfill. See OwnerSlot.mayActAsSlot for the two
        // cases that stay open (no identity header, and a seat Sleeper has not
        // mapped to anyone yet).
        //
        // 403 rather than the 404 this file uses elsewhere: those hide whether a
        // draft exists, and this caller has already been shown the whole board,
        // so there is nothing left to conceal and a reason they can act on is
        // worth more.
        if (!OwnerSlot.mayActAsSlot(draft, managers, sleeperUserId, slot)) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "pick " + pickNo + " belongs to draft slot " + slot + ", which is not your seat"));
        }

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

    /**
     * The `state` event's body. Package-private rather than private so it can be
     * asserted directly: the emitter it is normally written to is created inside
     * liveStream() and buffers its first send until Spring initializes the async
     * response, so there is no seam to read the payload back out of in a unit test.
     *
     * @param picks  this draft's stored picks, oldest first -- the caller supplies
     *               them because the initial frame already has the list in hand and
     *               re-querying for it would be a second read of the same rows.
     * @param namer  built once per stream; see {@link PickNaming}.
     */
    Map<String, Object> statePayload(String sleeperDraftId, DraftRepository.DraftRow draft,
                                    LiveDraftPoller.LiveSnapshot s,
                                    List<DraftRepository.PickRow> picks, PickNaming namer) {
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
        // The picks themselves, not just how many. Without these the live page
        // could only name a player once the *simulation* came back with him --
        // a debounce plus a full Monte Carlo run after the pick actually
        // landed, and nothing at all before the first projection ever returned.
        // These are facts the poller already wrote to draft_pick; the board
        // past picksMade stays the projection's job.
        m.put("recentPicks", namer.tail(picks, LIVE_RECENT_PICKS));
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
    public Map<String, Object> board(@RequestParam(defaultValue = "60") int limit,
                                     @RequestParam(defaultValue = "nfl") String sport) {
        Sport s = Sport.fromCode(sport);
        var entries = boards.currentBoard(s).stream()
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
                "capturedOn", boards.currentBoardDate(s).map(Object::toString).orElse("none"),
                "picksWithContemporaneousBoard", boards.picksWithAdpAtTime(),
                "entries", entries);
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
