package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.ScheduleGridService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NBA games per team per week (specs/017-nba-schedule-grid, contracts/api.md C1). The service's
 * records are the response, serialized by Jackson as-is.
 */
@RestController
@RequestMapping("/api")
public class ScheduleController {

    private final ScheduleGridService grid;
    private final LeagueMembership membership;

    public ScheduleController(ScheduleGridService grid, LeagueMembership membership) {
        this.grid = grid;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/schedule")
    public ResponseEntity<ScheduleGridService.Result> schedule(@PathVariable String sleeperId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every league route: no identity, or a stranger, is the same 404 as no league.
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return grid.forLeague(sleeperId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
