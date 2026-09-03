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

    /** Every mock session, newest first. Backs the picker screen's "Mock drafts" list. */
    @GetMapping
    public List<MockDraftRepository.SessionSummary> list() {
        return mocks.listSessions();
    }

    /**
     * Creates a session and auto-advances any bots picking before the user's
     * first turn. A slot in {@code managerSeats} is seeded with that real
     * manager's fitted/stated profile instead of an unmodelled bot; any slot
     * left out (besides {@code userSlot}) is still a plain bot.
     */
    @PostMapping
    public MockSessionState create(@RequestBody CreateRequest body) {
        if (body == null) throw new IllegalArgumentException("request body is required");
        return mocks.createSession(body.teams(), body.userSlot(), body.managerSeats());
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
                                            @RequestParam(required = false) Integer mySlot) {
        return mocks.createSessionFromDraft(sleeperDraftId, mySlot);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MockSessionState> get(@PathVariable long id) {
        return mocks.get(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Records the user's pick and auto-advances bots to the user's next turn (or the end). */
    @PostMapping("/{id}/pick")
    public ResponseEntity<?> pick(@PathVariable long id, @RequestBody(required = false) PickRequest body) {
        if (body == null || body.sleeperPlayerId() == null || body.sleeperPlayerId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sleeperPlayerId is required"));
        }
        return mocks.submitPick(id, body.sleeperPlayerId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record PickRequest(String sleeperPlayerId) {}
}
