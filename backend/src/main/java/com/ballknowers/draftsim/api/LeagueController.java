package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
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

    public LeagueController(LeagueRepository leagues, DraftRepository drafts,
                            ProfileService profiles, BoardService boards, LiveDraftPoller poller) {
        this.leagues = leagues;
        this.drafts = drafts;
        this.profiles = profiles;
        this.boards = boards;
        this.poller = poller;
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
        List<String> rosterPositions = leagues.byId(draft.get().leagueId())
                .map(LeagueRepository.LeagueRow::rosterPositions)
                .orElseGet(List::of);

        // Map.of(...) throws NullPointerException on any null value (AGENTS.md's
        // own named hard rule) -- board() below already had to switch to a mutable
        // LinkedHashMap for the identical reason. This response is about to gain
        // sibling nullable fields from other in-flight work on this same endpoint
        // (plan A's mySlot), so it needs the same fix now rather than after the
        // first null crashes it at runtime.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", sleeperDraftId);
        body.put("teams", draft.get().teams());
        body.put("rounds", draft.get().rounds());
        body.put("status", String.valueOf(draft.get().status()));
        body.put("seats", seats);
        body.put("rosterPositions", rosterPositions);
        return ResponseEntity.ok(body);
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
