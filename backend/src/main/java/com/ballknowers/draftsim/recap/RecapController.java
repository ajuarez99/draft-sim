package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The AI weekly recap (specs/020-ai-weekly-recap; contracts/api.md). */
@RestController
@RequestMapping("/api")
public class RecapController {

    private final RecapService recap;
    private final LeagueMembership membership;

    public RecapController(RecapService recap, LeagueMembership membership) {
        this.recap = recap;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/recap/{week}")
    public ResponseEntity<Map<String, Object>> recap(@PathVariable String sleeperId, @PathVariable int week,
                                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped exactly like /weekly-report: no identity or a non-member is the same 404 as an unknown league.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        if (week < 1) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(body(recap.view(sleeperId, week)));
    }

    /* package-private so a shape test can pin it without a database. */
    static Map<String, Object> body(RecapView v) {
        // Mutable on purpose: Map.of throws on the nulls most of these carry.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", v.state().name());
        out.put("season", v.season());
        out.put("week", v.week());
        out.put("model", v.model());
        out.put("generatedAt", v.generatedAt() == null ? null : v.generatedAt().toString());
        out.put("revision", v.revision());
        out.put("revisionReason", v.revisionReason());
        out.put("stale", v.stale());
        out.put("headline", v.headline());
        out.put("sections", v.sections());
        out.put("failureReason", v.failureReason());
        return out;
    }
}
