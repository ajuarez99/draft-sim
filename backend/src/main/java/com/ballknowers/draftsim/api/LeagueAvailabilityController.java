package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.LeagueAvailability;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/leagues/{key}/availability} (spec 035 contracts/availability.md): which pages this league
 * can honestly show. The body is {@link LeagueAvailability.View} as built by {@link LeagueAvailability}, the
 * same object the server-side refusals read, so the frontend gate and the 422s cannot disagree.
 *
 * <p>Scoped like every league route: no identity, or a league that is not yours, is the same 404 as a
 * league that does not exist.
 */
@RestController
@RequestMapping("/api")
public class LeagueAvailabilityController {

    private final LeagueAvailability availability;
    private final LeagueMembership membership;

    public LeagueAvailabilityController(LeagueAvailability availability, LeagueMembership membership) {
        this.availability = availability;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/availability")
    public ResponseEntity<LeagueAvailability.View> get(@PathVariable String sleeperId,
                                                       @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        return membership.visibleLeague(sleeperId, sleeperUserId)
                .map(l -> ResponseEntity.ok(availability.of(l, sleeperUserId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
