package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.SeasonStartService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the league's season is, and what the home shows for it (specs/030-post-draft-season-view,
 * contracts/wire-final.md section 3). The service's records are the response, serialized by Jackson
 * as-is. Scoped exactly like {@link NextMatchupController}: a league that does not exist, or that the
 * caller cannot see, is a 404.
 */
@RestController
@RequestMapping("/api")
public class SeasonStartController {

    private final SeasonStartService seasonStart;
    private final LeagueMembership membership;

    public SeasonStartController(SeasonStartService seasonStart, LeagueMembership membership) {
        this.seasonStart = seasonStart;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/season-start")
    public ResponseEntity<SeasonStartService.SeasonStart> seasonStart(@PathVariable String sleeperId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return seasonStart.read(sleeperId, sleeperUserId)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
