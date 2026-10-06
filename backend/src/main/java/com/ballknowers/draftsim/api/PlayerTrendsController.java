package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.PlayerTrendsService;
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
 * NBA minutes trends and streaming candidates (specs/019-minutes-streaming, contract C1). The
 * service's records are the response, serialized by Jackson as-is. Scoped like every league route:
 * no identity, or a stranger, is the same 404 as no league.
 */
@RestController
@RequestMapping("/api")
public class PlayerTrendsController {

    private final PlayerTrendsService trends;
    private final LeagueMembership membership;

    public PlayerTrendsController(PlayerTrendsService trends, LeagueMembership membership) {
        this.trends = trends;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperLeagueId}/player-trends")
    public ResponseEntity<PlayerTrendsService.PlayerTrends> playerTrends(@PathVariable String sleeperLeagueId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperLeagueId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(trends.read(league.get(), sleeperUserId));
    }
}
