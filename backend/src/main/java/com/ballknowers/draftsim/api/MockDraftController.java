package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.mock.MockDraftService;
import com.ballknowers.draftsim.mock.MockSessionState;
import com.ballknowers.draftsim.store.MockDraftRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The interactive mock draft room (claude/next-features-roadmap.md §4, Phase 3).
 *
 * No SSE, no bot-advance endpoint: every mutating call returns the fully-advanced
 * state in one response (see {@link MockDraftService}'s own doc comment for why).
 */
@RestController
@RequestMapping("/api/mocks")
public class MockDraftController {

    private final MockDraftService mocks;

    public MockDraftController(MockDraftService mocks) {
        this.mocks = mocks;
    }

    /**
     * This caller's mock sessions, newest first. Backs the picker screen's
     * "Mock drafts" list.
     *
     * Scoped by {@code X-Sleeper-User} (V8). Before that this returned every
     * session in the database to every caller, which with one user was
     * invisible and with two meant the picker listed strangers' drafts.
     */
    @GetMapping
    public List<MockDraftRepository.SessionSummary> list(
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        return mocks.listSessions(sleeperUserId);
    }

    /**
     * Creates a session and auto-advances any bots picking before the user's
     * first turn. A slot in {@code managerSeats} is seeded with that real
     * manager's fitted/stated profile instead of an unmodelled bot; any slot
     * left out (besides {@code userSlot}) is still a plain bot.
     */
    @PostMapping
    public MockSessionState create(@RequestBody CreateRequest body,
                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        return mocks.createSession(body.teams(), body.userSlot(), body.managerSeats(), sleeperUserId);
    }

    public record CreateRequest(int teams, int userSlot, Map<Integer, Long> managerSeats) {
        public CreateRequest {
            if (managerSeats == null) managerSeats = Map.of();
        }
    }

    /**
     * Forks a real, {@code drafting}-status Sleeper draft into a new mock
     * session seeded with its picks so far -- the live-draft-to-mock bridge
     * (claude/next-features-roadmap.md's Phase 3/4 bridge). {@code mySlot} is
     * optional; omitted, it falls back to the same owner auto-detection
     * {@code GET /api/drafts/{id}/seats} already uses.
     */
    @PostMapping("/from-draft/{sleeperDraftId}")
    public MockSessionState createFromDraft(@PathVariable String sleeperDraftId,
                                            @RequestParam(required = false) Integer mySlot,
                                            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        return mocks.createSessionFromDraft(sleeperDraftId, mySlot, sleeperUserId);
    }

    /** Someone else's session is a 404 here, same as one that doesn't exist -- see MockDraftService.get. */
    @GetMapping("/{id}")
    public ResponseEntity<MockSessionState> get(@PathVariable long id,
                                                @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        return mocks.get(id, sleeperUserId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Records the user's pick and auto-advances bots to the user's next turn
     * (or the end).
     *
     * The one endpoint in the app where an unscoped call did not merely read
     * someone else's data but overwrote it: before V8 any caller could submit
     * a pick into any in-progress session. Now a session owned by someone else
     * 404s here exactly as it does on GET.
     */
    @PostMapping("/{id}/pick")
    public ResponseEntity<?> pick(@PathVariable long id, @RequestBody(required = false) PickRequest body,
                                  @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (body == null || body.sleeperPlayerId() == null || body.sleeperPlayerId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sleeperPlayerId is required"));
        }
        return mocks.submitPick(id, body.sleeperPlayerId(), sleeperUserId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record PickRequest(String sleeperPlayerId) {}
}
