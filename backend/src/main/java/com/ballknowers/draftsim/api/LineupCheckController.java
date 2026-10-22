package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.LineupCheck;
import com.ballknowers.draftsim.engine.LineupCheckService;
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
 * The lineup check (specs/042-fantasypros-design-features US3, contracts/lineup-check.md). The service's
 * records are the response. Scoped like every league route: no identity, or a stranger, is the same 404 as
 * no league (review F7); every handled case after that, including an explained absence, is a 200.
 * ESPN leagues are refused before they get here by {@code PlatformGuard} (the {@code lineupCheck} key).
 */
@RestController
@RequestMapping("/api")
public class LineupCheckController {

    private final LineupCheckService lineupCheck;
    private final LeagueMembership membership;

    public LineupCheckController(LineupCheckService lineupCheck, LeagueMembership membership) {
        this.lineupCheck = lineupCheck;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperLeagueId}/lineup-check")
    public ResponseEntity<LineupCheck> lineupCheck(@PathVariable String sleeperLeagueId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = membership.visibleLeague(sleeperLeagueId, sleeperUserId);
        if (league.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(lineupCheck.forLeague(league.get(), sleeperUserId));
    }
}
