package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.PlayerStatsService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * The NBA player page (specs/022-player-stat-analysis, contract C1). The service's records are the
 * response, serialized by Jackson as-is. Scoped like every league route: no identity, or a stranger, is
 * the same 404 as no league. A player id with no games in the answered season and no player row is a 404
 * too.
 */
@RestController
@RequestMapping("/api")
public class PlayerStatsController {

    private final PlayerStatsService stats;
    private final LeagueMembership membership;

    public PlayerStatsController(PlayerStatsService stats, LeagueMembership membership) {
        this.stats = stats;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}")
    public ResponseEntity<PlayerStatsService.PlayerStatsPage> player(@PathVariable String sleeperLeagueId,
            @PathVariable String sleeperPlayerId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperLeagueId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        return stats.read(league.get(), sleeperPlayerId, sleeperUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
