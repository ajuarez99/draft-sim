package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.ProjectionAccuracyService;
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
 * The full projection-accuracy breakdown (specs/036-projection-foundation US2, contracts/accuracy-api.md).
 *
 * <p>Scoped exactly like {@link LeagueAnalysisController}: a league the caller has nothing to do with is
 * a 404, never a 403. The figures depend on the league's own scoring, so the route is league-addressed even
 * though the snapshots are shared by every league of the sport.
 */
@RestController
@RequestMapping("/api")
public class ProjectionAccuracyController {

    private final ProjectionAccuracyService accuracy;
    private final LeagueRepository leagues;
    private final LeagueMembership membership;

    public ProjectionAccuracyController(ProjectionAccuracyService accuracy, LeagueRepository leagues,
                                        LeagueMembership membership) {
        this.accuracy = accuracy;
        this.leagues = leagues;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/projection-accuracy")
    public ResponseEntity<?> projectionAccuracy(@PathVariable String sleeperId,
                                                @RequestHeader(value = "X-Sleeper-User", required = false)
                                                String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = leagues.bySleeperId(sleeperId);
        if (league.isEmpty() || !membership.canSee(sleeperUserId, league.get().id())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(accuracy.forLeague(league.get()));
    }
}
