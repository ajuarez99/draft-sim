package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.AdpCompareService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spec 046 US1 (contracts/adp-compare-api.md, amended): the ADP compare page's one read. Scoped like every
 * league route -- identity from {@code X-Sleeper-User}, a stranger or no identity is the same 404 as no league.
 * No platform gate (F13): the ADP sources are sport-wide, so an ESPN NBA league gets the compare half with
 * {@code draft: null}. A football league has no compare sources and is a 404. The service's records are the
 * response.
 */
@RestController
@RequestMapping("/api")
public class AdpCompareController {

    private final AdpCompareService compare;
    private final LeagueMembership membership;

    public AdpCompareController(AdpCompareService compare, LeagueMembership membership) {
        this.compare = compare;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperLeagueId}/adp-compare")
    public ResponseEntity<AdpCompareService.Response> adpCompare(@PathVariable String sleeperLeagueId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        return membership.visibleLeague(sleeperLeagueId, sleeperUserId)
                .flatMap(l -> compare.forLeague(l, sleeperUserId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
