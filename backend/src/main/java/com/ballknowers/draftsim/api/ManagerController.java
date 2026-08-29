package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ManualTendencies;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.ManagerProfileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Reading and setting what the user believes about each manager.
 *
 * There is no UI for this yet — drive it with curl or Postman. The engine reads
 * these values on the next simulation with no restart needed.
 */
@RestController
@RequestMapping("/api/managers")
public class ManagerController {

    private final ProfileService profiles;
    private final ManagerProfileRepository repo;

    public ManagerController(ProfileService profiles, ManagerProfileRepository repo) {
        this.profiles = profiles;
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        ProfileService.Fit fit = profiles.fit(Sport.NFL);
        List<Map<String, Object>> out = new ArrayList<>();
        fit.profiles().values().forEach(p -> out.add(describe(p, repo.manualFor(p.managerId(), Sport.NFL))));
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("manager"))));
        return out;
    }

    /**
     * Body may set any subset. An explicit null clears that field; omitting it is
     * the same as sending null, because the whole manual blob is replaced.
     *
     *   { "reachBias": 8, "unpredictability": 1.6, "note": "drafts his own Bengals" }
     */
    @PutMapping("/{managerId}/tendencies")
    public ResponseEntity<Map<String, Object>> set(@PathVariable long managerId,
                                                   @RequestBody ManualTendencies body) {
        ManualTendencies stored = body == null ? ManualTendencies.EMPTY : body;
        profiles.setManual(managerId, Sport.NFL, stored);

        return profiles.fit(Sport.NFL).profiles().get(managerId) instanceof ManagerProfile p
                ? ResponseEntity.ok(describe(p, stored))
                : ResponseEntity.ok(Map.of("managerId", managerId, "stored", stored));
    }

    @DeleteMapping("/{managerId}/tendencies")
    public Map<String, Object> clear(@PathVariable long managerId) {
        profiles.setManual(managerId, Sport.NFL, ManualTendencies.EMPTY);
        return Map.of("managerId", managerId, "cleared", true);
    }

    private static Map<String, Object> describe(ManagerProfile p, ManualTendencies manual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("managerId", p.managerId());
        m.put("manager", p.displayName());
        m.put("provenance", p.provenance().name());
        // The value the engine will actually use, after blending.
        m.put("effectiveReachBias", Math.round(p.reachBias() * 100) / 100.0);
        m.put("unpredictability", p.unpredictability());
        m.put("positionalTilt", p.positionalTilt());
        m.put("note", p.note());
        m.put("draftsObserved", p.draftsObserved());
        m.put("picksScored", p.picksScored());
        // What you typed, so the difference between the two is visible.
        Map<String, Object> stated = new LinkedHashMap<>();
        stated.put("reachBias", manual.reachBias());
        stated.put("unpredictability", manual.unpredictability());
        stated.put("note", manual.note());
        m.put("stated", stated);
        return m;
    }
}
