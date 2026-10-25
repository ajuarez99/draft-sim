package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.HallOfFameService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hall of fame's reads (specs/047-hall-of-fame, contracts C1/C3). The service's records are the response,
 * serialized by Jackson as-is.
 */
@RestController
@RequestMapping("/api")
public class HallOfFameController {

    private final HallOfFameService hallOfFame;
    private final LeagueMembership membership;

    public HallOfFameController(HallOfFameService hallOfFame, LeagueMembership membership) {
        this.hallOfFame = hallOfFame;
        this.membership = membership;
    }

    /**
     * A manager's nemesis and victim per league chain (contracts C3). 404 unless the caller shares a league with
     * the manager ({@code canSeeManager}, the same gate as {@code /managers/{id}/history}). The chains returned are
     * restricted to the caller's own leagues, so sharing one league never reveals the manager's others; an operator
     * (no identity, valid admin token) sees every chain.
     */
    @GetMapping("/managers/{managerId}/rivals")
    public ResponseEntity<HallOfFameService.ManagerRivals> rivals(@PathVariable long managerId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (!membership.canSeeManager(sleeperUserId, managerId)) return ResponseEntity.notFound().build();
        // canSeeManager lets a caller with no identity through only as an operator, who sees every league (null).
        java.util.Set<Long> visible = LeagueMembership.isAnonymous(sleeperUserId) ? null : membership.leagueIdsFor(sleeperUserId);
        return hallOfFame.rivals(managerId, visible).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Scoped exactly like {@code /rivalries}: no identity, or a stranger, is the same 404 as no league. */
    @GetMapping("/leagues/{sleeperId}/trophy-case")
    public ResponseEntity<HallOfFameService.TrophyCase> trophyCase(@PathVariable String sleeperId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return hallOfFame.trophyCase(sleeperId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
