package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class LeagueController {

    private final LeagueRepository leagues;
    private final DraftRepository drafts;
    private final ProfileService profiles;
    private final BoardService boards;
    private final LiveDraftPoller poller;
    private final ManagerRepository managers;
    private final OwnerProperties owner;

    public LeagueController(LeagueRepository leagues, DraftRepository drafts,
                            ProfileService profiles, BoardService boards, LiveDraftPoller poller,
                            ManagerRepository managers, OwnerProperties owner) {
        this.leagues = leagues;
        this.drafts = drafts;
        this.profiles = profiles;
        this.boards = boards;
        this.poller = poller;
        this.managers = managers;
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

        // Map.of rejects null values, and mySlot is null in the default case --
        // unset config, or a configured owner who isn't a manager in this
        // particular league -- i.e. the state every fresh checkout starts in.
        // LinkedHashMap tolerates the null directly, same fix board() below
        // already applies for its own nullable field.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftId", sleeperDraftId);
        response.put("teams", draft.get().teams());
        response.put("rounds", draft.get().rounds());
        response.put("status", String.valueOf(draft.get().status()));
        response.put("seats", seats);
        response.put("mySlot", mySlotHolder[0]);
        return ResponseEntity.ok(response);
    }

    /** Starts (or confirms) live polling for a draft. Safe to call any time before it goes live. */
    @PostMapping("/drafts/{sleeperDraftId}/track")
    public ResponseEntity<?> track(@PathVariable String sleeperDraftId) {
        Optional<DraftRepository.DraftRow> draft = drafts.bySleeperId(sleeperDraftId);
        if (draft.isEmpty()) return ResponseEntity.notFound().build();
        LiveDraftPoller.TrackResult r = poller.track(draft.get());
        return ResponseEntity.ok(Map.of(
                "draftId", sleeperDraftId, "tracking", true,
                "alreadyTracking", !r.started(), "status", r.status()));
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
