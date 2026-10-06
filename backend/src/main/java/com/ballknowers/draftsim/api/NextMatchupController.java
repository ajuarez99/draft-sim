package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.NextMatchupService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's opponent for the league's current week (specs/017-nba-schedule-grid,
 * contracts/api.md C2). The service's records are the response, serialized by Jackson as-is.
 */
@RestController
@RequestMapping("/api")
public class NextMatchupController {

    private final NextMatchupService nextMatchup;
    private final LeagueMembership membership;

    public NextMatchupController(NextMatchupService nextMatchup, LeagueMembership membership) {
        this.nextMatchup = nextMatchup;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/next-matchup")
    public ResponseEntity<NextMatchupService.Result> nextMatchup(@PathVariable String sleeperId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return nextMatchup.forLeague(sleeperId, sleeperUserId)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
