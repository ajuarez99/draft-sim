package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.LeagueAnalysisService;
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
 * The League analysis page's one read (claude/league-analysis.md).
 *
 * <p>Its own controller rather than a fifth route on
 * {@link LeagueHistoryController}: that file is the power-rankings surface --
 * ladders, ballots, the commissioner ordering -- and this page deliberately
 * answers a different question ("what is this roster made of", not "who is
 * best"). The split is the same one the brief makes between the two pages.
 *
 * <p>Scoping is identical to every other league-addressed route: a league the
 * caller has nothing to do with is a 404, not a 403, for the same reason
 * {@link LeagueMembership#visibleDraft} gives.
 */
@RestController
@RequestMapping("/api")
public class LeagueAnalysisController {

    private final LeagueAnalysisService analysis;
    private final LeagueRepository leagues;
    private final LeagueMembership membership;

    public LeagueAnalysisController(LeagueAnalysisService analysis, LeagueRepository leagues,
                                    LeagueMembership membership) {
        this.analysis = analysis;
        this.leagues = leagues;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/analysis")
    public ResponseEntity<?> analysis(@PathVariable String sleeperId,
                                      @RequestHeader(value = "X-Sleeper-User", required = false)
                                      String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> league = leagues.bySleeperId(sleeperId);
        if (league.isEmpty() || !membership.canSee(sleeperUserId, league.get().id())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(analysis.analyse(sleeperId, sleeperUserId));
    }
}
