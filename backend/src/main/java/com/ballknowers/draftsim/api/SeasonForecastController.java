package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.PlayoffOddsService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Season Forecast and the playoff picture
 * (specs/004-ffwrapped-feature-parity, US4;
 * contracts/league-analytics-api.md).
 *
 * <p>Read-only over the stored snapshot. Nothing here computes: the numbers
 * come from the simulation the commissioner's recompute already ran, which is
 * what keeps this view and the Record cell on the same figure (FR-008) and
 * preserves the existing rule that a page load never simulates (FR-009).
 */
@RestController
@RequestMapping("/api")
public class SeasonForecastController {

    private final PlayoffOddsService playoffOdds;
    private final LeagueMembership membership;

    public SeasonForecastController(PlayoffOddsService playoffOdds, LeagueMembership membership) {
        this.playoffOdds = playoffOdds;
        this.membership = membership;
    }

    @GetMapping("/leagues/{sleeperId}/forecast")
    public ResponseEntity<Map<String, Object>> forecast(@PathVariable String sleeperId,
                                                        @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Scoped like every other league route: no identity, or an identity that is not in
        // this league, is the same 404 as a league that does not exist. This route had no
        // scoping at all before claude/audit-2026-09-28/01, so ANY caller could read it.
        var visible = membership.visibleLeague(sleeperId, sleeperUserId);
        if (visible.isEmpty()) return ResponseEntity.notFound().build();
        // Display only: whether to OFFER the recompute button. The route itself enforces
        // the admin token and the commissioner identity (POST /power/compute).
        boolean canCommission = membership.canCommission(visible.get().id(), sleeperUserId);
        return playoffOdds.forecast(sleeperId)
                .map(f -> ResponseEntity.ok(body(f, canCommission)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* package-private so the contract test can pin the shape without a database. */
    static Map<String, Object> body(PlayoffOddsService.Forecast f, boolean canCommission) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", f.available());
        out.put("season", f.season());
        out.put("requestedSeason", f.requestedSeason());
        // How far the league has scored, beside the week the snapshot was taken at, so the page
        // can say the forecast is behind. Present on every shape, refusals included.
        // Stored, and FINAL (see ScoredWeeks): the notice counts final weeks only, so an
        // in-progress week never reads as "a week scored since this forecast".
        out.put("latestScoredWeek", f.latestScoredWeek());
        out.put("latestFinalWeek", f.latestFinalWeek());
        // A finished season has nothing left to simulate; the page says so instead of showing
        // the final snapshot as a live forecast. The league row's own status, on every shape.
        out.put("seasonComplete", f.seasonComplete());
        out.put("canCommission", canCommission);
        if (!f.available()) {
            // A named reason, not an empty table: "this league's seeding is not
            // modelled" and "no week has been scored" call for different words
            // on the page, and neither is a zero.
            out.put("reason", f.reason().name());
            out.put("teams", List.of());
            return out;
        }
        out.put("week", f.week());
        out.put("iterations", f.iterations());
        out.put("model", f.model());
        List<Map<String, Object>> teams = new ArrayList<>();
        for (PlayoffOddsService.ForecastTeam t : f.teams()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rosterId", t.rosterId());
            row.put("managerId", t.managerId());
            row.put("teamName", t.teamName());
            row.put("username", t.username());
            row.put("avatarId", t.avatarId());
            row.put("playoffOdds", t.playoffOdds());
            row.put("averageWins", t.averageWins());
            row.put("projectedPoints", t.projectedPoints());
            // Null rather than 0 for a snapshot taken before distributions were
            // stored: that simulation is gone and re-running it now would answer
            // a different question, so "no range" is the truth.
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("p10", t.winP10());
            range.put("p90", t.winP90());
            row.put("winRange", range);
            row.put("averageSeed", t.averageSeed());
            row.put("seedOnePct", t.seedOnePct());
            Map<String, Object> seedOdds = new LinkedHashMap<>();
            t.seedOdds().forEach((seed, pct) -> seedOdds.put(String.valueOf(seed), pct));
            row.put("seedOdds", seedOdds);
            teams.add(row);
        }
        out.put("teams", teams);
        return out;
    }
}
