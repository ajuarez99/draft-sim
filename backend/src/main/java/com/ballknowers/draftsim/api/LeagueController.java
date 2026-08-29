package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.BoardService;
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

    public LeagueController(LeagueRepository leagues, DraftRepository drafts,
                            ProfileService profiles, BoardService boards) {
        this.leagues = leagues;
        this.drafts = drafts;
        this.profiles = profiles;
        this.boards = boards;
    }

    @GetMapping("/leagues")
    public List<LeagueRepository.LeagueRow> leagues() {
        return leagues.all();
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
            seats.add(new LinkedHashMap<>(Map.of(
                    "slot", Integer.parseInt(slot),
                    "manager", p.displayName(),
                    "reachBias", round2(p.reachBias()),
                    "positionalTilt", p.positionalTilt(),
                    "draftsObserved", p.draftsObserved(),
                    "picksScored", p.picksScored())));
        });
        seats.sort(Comparator.comparingInt(s -> (Integer) s.get("slot")));

        return ResponseEntity.ok(Map.of(
                "draftId", sleeperDraftId,
                "teams", draft.get().teams(),
                "rounds", draft.get().rounds(),
                "status", String.valueOf(draft.get().status()),
                "seats", seats));
    }

    /** What the engine is valuing against, so it can be eyeballed before trusting a sim. */
    @GetMapping("/board")
    public Map<String, Object> board(@RequestParam(defaultValue = "60") int limit) {
        var entries = boards.currentBoard(Sport.NFL).stream()
                .limit(limit)
                .map(e -> Map.of(
                        "adp", e.adp(),
                        "name", e.player().name(),
                        "position", e.position().name(),
                        "team", String.valueOf(e.player().team()),
                        "positionalRank", e.positionalRank()))
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
