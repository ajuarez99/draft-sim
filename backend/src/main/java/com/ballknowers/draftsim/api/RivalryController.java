package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.RivalryService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rivalry page's one read (specs/034-rivalry-schedule-grid, contracts C1). The service's records are
 * the response, serialized by Jackson as-is.
 */
@RestController
@RequestMapping("/api")
public class RivalryController {

    private final RivalryService rivalries;
    private final LeagueMembership membership;

    public RivalryController(RivalryService rivalries, LeagueMembership membership) {
        this.rivalries = rivalries;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/rivalries")
    public ResponseEntity<RivalryService.Response> rivalries(@PathVariable String sleeperId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every league route: no identity, or a stranger, is the same 404 as no league.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return rivalries.forLeague(sleeperId, sleeperUserId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
