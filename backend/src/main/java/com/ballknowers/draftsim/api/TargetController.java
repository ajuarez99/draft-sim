package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.mock.MockDraftService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.DraftTargetRepository;
import com.ballknowers.draftsim.store.DraftTargetRepository.DraftScope;
import com.ballknowers.draftsim.store.DraftTargetRepository.MockScope;
import com.ballknowers.draftsim.store.DraftTargetRepository.TargetScope;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A person's draft targets (spec 024 US3): one ordered, private list per draft or mock.
 *
 * <p>Identity is {@code X-Sleeper-User}. Reading with none returns an empty list; writing with none is a
 * 401 even with the admin token, because a target row needs an owner. A draft or mock the caller cannot
 * see is a 404 on both verbs, never a 403 -- the same collapse {@code visibleDraft} gives every other
 * draft route.
 */
@RestController
@RequestMapping("/api/targets")
public class TargetController {

    // ARBITRARY cap: a guess at a sane upper bound on a person's target list, not derived from anything.
    static final int MAX_TARGETS = 50;

    /** A target whose player is no longer on the board: no ADP is invented for it. */
    public record MissingTarget(String sleeperId, String name) {}

    public record DraftTargets(List<SimulationResult.PlayerRef> players, List<MissingTarget> missing) {}

    /** {@code sleeperDraftId} xor {@code mockSessionId}; the body replaces the whole list. */
    public record PutRequest(String sleeperDraftId, Long mockSessionId, List<String> sleeperPlayerIds) {}

    private final DraftTargetRepository targets;
    private final LeagueMembership membership;
    private final LeagueRepository leagues;
    private final MockDraftService mocks;
    private final BoardService boards;
    private final PlayerRepository players;

    public TargetController(DraftTargetRepository targets, LeagueMembership membership, LeagueRepository leagues,
                            MockDraftService mocks, BoardService boards, PlayerRepository players) {
        this.targets = targets;
        this.membership = membership;
        this.leagues = leagues;
        this.mocks = mocks;
        this.boards = boards;
        this.players = players;
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestParam(required = false) String sleeperDraftId,
                                 @RequestParam(required = false) Long mockSessionId,
                                 @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireOneScope(sleeperDraftId, mockSessionId);
        if (LeagueMembership.isAnonymous(sleeperUserId)) {
            return ResponseEntity.ok(new DraftTargets(List.of(), List.of()));
        }
        Optional<Resolved> r = resolve(sleeperUserId, sleeperDraftId, mockSessionId);
        if (r.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(read(sleeperUserId, r.get()));
    }

    @PutMapping
    public ResponseEntity<?> put(@RequestBody(required = false) PutRequest body,
                                 @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Before anything else, and before the admin override could apply: no owner, no row.
        if (LeagueMembership.isAnonymous(sleeperUserId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Sleeper-User is required to save targets");
        }
        if (body == null) throw new IllegalArgumentException("request body is required");
        requireOneScope(body.sleeperDraftId(), body.mockSessionId());
        Optional<Resolved> r = resolve(sleeperUserId, body.sleeperDraftId(), body.mockSessionId());
        if (r.isEmpty()) return ResponseEntity.notFound().build();

        List<String> ids = body.sleeperPlayerIds();
        if (ids == null) {
            throw new IllegalArgumentException("sleeperPlayerIds is required (an empty array clears the list)");
        }
        if (ids.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("at most " + MAX_TARGETS + " targets, got " + ids.size());
        }
        Map<String, Long> idsBySleeper = players.idsBySleeperId(r.get().sport());
        Set<String> seen = new HashSet<>();
        List<Long> playerIds = new ArrayList<>(ids.size());
        for (String sleeperId : ids) {
            if (sleeperId == null || sleeperId.isBlank()) throw new IllegalArgumentException("blank sleeper player id");
            if (!seen.add(sleeperId)) throw new IllegalArgumentException("duplicate target: " + sleeperId);
            Long id = idsBySleeper.get(sleeperId);
            if (id == null) throw new IllegalArgumentException("unknown sleeper player id: " + sleeperId);
            playerIds.add(id);
        }
        targets.replace(sleeperUserId, r.get().scope(), playerIds);
        return ResponseEntity.ok(read(sleeperUserId, r.get()));
    }

    private record Resolved(TargetScope scope, Sport sport) {}

    private static void requireOneScope(String sleeperDraftId, Long mockSessionId) {
        boolean draft = sleeperDraftId != null && !sleeperDraftId.isBlank();
        if (draft == (mockSessionId != null)) {
            throw new IllegalArgumentException("pass exactly one of sleeperDraftId or mockSessionId");
        }
    }

    /** Scope and sport, or empty (-> 404) when the caller cannot see the draft or mock. */
    private Optional<Resolved> resolve(String user, String sleeperDraftId, Long mockSessionId) {
        if (mockSessionId != null) {
            return mocks.usableSport(mockSessionId, user)
                    .map(sport -> new Resolved(new MockScope(mockSessionId), sport));
        }
        Optional<DraftRepository.DraftRow> draft = membership.visibleDraft(user, sleeperDraftId);
        if (draft.isEmpty()) return Optional.empty();
        // The sport comes from the draft's league, as seats() does.
        Sport sport = leagues.byId(draft.get().leagueId()).map(LeagueRepository.LeagueRow::sport).orElse(Sport.NFL);
        return Optional.of(new Resolved(new DraftScope(sleeperDraftId), sport));
    }

    private DraftTargets read(String user, Resolved r) {
        Map<Long, BoardEntry> byId = new HashMap<>();
        for (BoardEntry e : boards.currentBoard(r.sport())) byId.put(e.player().id(), e);
        List<SimulationResult.PlayerRef> onBoard = new ArrayList<>();
        List<MissingTarget> missing = new ArrayList<>();
        for (DraftTargetRepository.Target t : targets.list(user, r.scope())) {
            BoardEntry e = byId.get(t.playerId());
            if (e != null) onBoard.add(SimulationResult.PlayerRef.from(e));
            else missing.add(new MissingTarget(t.sleeperId(), t.name()));
        }
        return new DraftTargets(onBoard, missing);
    }
}
