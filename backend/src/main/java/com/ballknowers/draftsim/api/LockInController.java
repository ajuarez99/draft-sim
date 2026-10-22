package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.LockInService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Lock-in margins for one league-season (specs/044-lock-in-threshold US1, contracts/lock-in-api.md).
 *
 * <p>Scoped exactly like {@link ProjectionAccuracyController}: a league the caller has nothing to do with is a 404,
 * never a 403. {@code player} narrows the answer to one player's summary and locks; {@code live} (default true)
 * makes the live Lock/Wait calls for a league still being played, and false skips the Sleeper roster read.
 * {@code live} defaults to true for the league report and false when {@code player} is set (a player answer has no
 * use for a league's roster read); {@code live=true} with {@code player} returns only that player's call.
 */
@RestController
@RequestMapping("/api")
public class LockInController {

    private final LockInService lockIns;
    private final LeagueRepository leagues;
    private final LeagueMembership membership;

    public LockInController(LockInService lockIns, LeagueRepository leagues, LeagueMembership membership) {
        this.lockIns = lockIns;
        this.leagues = leagues;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/lock-ins")
    public ResponseEntity<?> lockIns(@PathVariable String sleeperId,
                                     @RequestParam(value = "player", required = false) String player,
                                     @RequestParam(value = "live", required = false) Boolean live,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = leagues.bySleeperId(sleeperId);
        if (league.isEmpty() || !membership.canSee(sleeperUserId, league.get().id())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(lockIns.report(league.get(), player, live != null ? live : (player == null || player.isBlank())));
    }
}
