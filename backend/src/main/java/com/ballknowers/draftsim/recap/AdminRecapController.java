package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.engine.LeagueSeasonResolver;
import com.ballknowers.draftsim.store.LeagueFeatureRepository;
import com.ballknowers.draftsim.store.LeagueRecapRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator-only recap routes. Everything under {@code /api/admin/**} is behind
 * {@code AdminGateInterceptor} (WebConfig), so no handler here checks the token itself.
 */
@RestController
@RequestMapping("/api/admin/leagues/{sleeperId}")
public class AdminRecapController {

    private final LeagueSeasonResolver resolver;
    private final LeagueFeatureRepository features;
    private final LeagueRecapRepository recaps;
    private final RecapService recap;

    public AdminRecapController(LeagueSeasonResolver resolver, LeagueFeatureRepository features,
                                LeagueRecapRepository recaps, RecapService recap) {
        this.resolver = resolver;
        this.features = features;
        this.recaps = recaps;
        this.recap = recap;
    }

    /**
     * Grants on the PLAYED season row ({@link LeagueSeasonResolver}), the same row the recap
     * service resolves, so a grant given via a newer unplayed season id still takes effect (F5).
     * Idempotent; 404 for an unknown league.
     */
    @PostMapping("/features/RECAP")
    public ResponseEntity<Void> grant(@PathVariable String sleeperId) {
        var resolved = resolver.resolve(sleeperId);
        if (resolved.isEmpty()) return ResponseEntity.notFound().build();
        features.grant(resolved.get().league().id(), LeagueFeatureRepository.RECAP, "admin grant");
        return ResponseEntity.noContent().build();
    }

    /** Idempotent; 404 for an unknown league. */
    @DeleteMapping("/features/RECAP")
    public ResponseEntity<Void> revoke(@PathVariable String sleeperId) {
        var resolved = resolver.resolve(sleeperId);
        if (resolved.isEmpty()) return ResponseEntity.notFound().build();
        features.revoke(resolved.get().league().id(), LeagueFeatureRepository.RECAP);
        return ResponseEntity.noContent().build();
    }

    /**
     * The operator's "try again" (F1): forgets the last attempt and its retry_after so the next GET
     * generates. The READY body stays, and the caps still apply to the generation.
     */
    @PostMapping("/recap/{week}/regenerate")
    public ResponseEntity<Void> regenerate(@PathVariable String sleeperId, @PathVariable int week) {
        var resolved = resolver.resolve(sleeperId);
        if (resolved.isEmpty()) return ResponseEntity.notFound().build();
        recaps.clearAttempt(resolved.get().league().id(), week);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pulls a recap the human read found wrong (R12): clears the READY body and the last attempt, so
     * the next GET generates fresh (the caps still apply). Revision is kept. Idempotent; 404 for an
     * unknown league.
     */
    @PostMapping("/recap/{week}/reroll")
    public ResponseEntity<Void> reroll(@PathVariable String sleeperId, @PathVariable int week) {
        var resolved = resolver.resolve(sleeperId);
        if (resolved.isEmpty()) return ResponseEntity.notFound().build();
        recaps.reroll(resolved.get().league().id(), week);
        return ResponseEntity.noContent().build();
    }

    /**
     * One model call that is NOT stored (F2, the model bake-off): never reads or writes
     * {@code league_recap}; it logs a call against the caps and is refused with 429 {@code rate_limited} when they are spent.
     * 409 {@code recap_disabled} without a client bean.
     */
    @PostMapping("/recap/{week}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable String sleeperId, @PathVariable int week,
                                                       @RequestParam String model) {
        if (!recap.enabled()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "recap_disabled");
            return ResponseEntity.status(409).body(err);
        }
        RecapService.Preview p;
        try {
            var r = recap.preview(sleeperId, week, model);
            if (r.isEmpty()) return ResponseEntity.notFound().build();
            p = r.get();
        } catch (RecapService.CapSpentException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "rate_limited");
            return ResponseEntity.status(429).body(err);
        } catch (RecapUpstreamException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.kind().name());
            return ResponseEntity.status(502).body(err);
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", p.inputTokens());
        usage.put("outputTokens", p.outputTokens());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("output", p.output());
        out.put("grounding", p.grounding());
        out.put("usage", usage);
        out.put("stopReason", p.stopReason());
        out.put("latencyMs", p.latencyMs());
        out.put("parseError", p.parseError());
        return ResponseEntity.ok(out);
    }
}
