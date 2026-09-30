package com.ballknowers.draftsim.refresh;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refresh-on-visit endpoints (specs/009-auto-data-refresh,
 * contracts/refresh-api.md).
 *
 * <p>Both take {@code X-Sleeper-User} and answer 404 for a league the caller
 * cannot see, collapsing "no such league" and "not yours" the way every league
 * endpoint does. The bodies are built as mutable maps: {@code lastSuccessAt} and
 * {@code lastFailureAt} are legitimately null, and {@code Map.of} throws on a
 * null value (AGENTS.md).
 *
 * <p>Also the two daily-job routes: {@code POST /api/refresh/daily}, guarded by
 * its own secret and exempt from {@code ApiTokenFilter} (research R8), and
 * {@code POST /api/refresh/players}, the setup flow's gated player-list fetch,
 * which goes through the normal token gate like every other call the web app makes.
 */
@RestController
@RequestMapping("/api")
public class RefreshController {

    private final LeagueRefreshService refresh;
    private final LeagueMembership membership;
    private final DailyRefreshService daily;
    private final RefreshProperties props;

    public RefreshController(LeagueRefreshService refresh, LeagueMembership membership,
                             DailyRefreshService daily, RefreshProperties props) {
        this.refresh = refresh;
        this.membership = membership;
        this.daily = daily;
        this.props = props;
    }

    /**
     * The scheduled job's only call. Synchronous, so the job's exit status is the
     * result: 404 when no secret is configured (the route doesn't exist), 401 on a
     * missing or wrong secret, then 200, or 500 if any non-skipped step failed. The
     * body is present either way.
     */
    @PostMapping("/refresh/daily")
    public ResponseEntity<?> daily(@RequestHeader(value = "X-Refresh-Secret", required = false) String presented) {
        if (!props.dailyRouteConfigured()) return ResponseEntity.notFound().build();
        if (!props.matchesSecret(presented)) return ResponseEntity.status(401).build();
        DailyRefreshService.DailyResult result = daily.runAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", result.date().toString());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (DailyRefreshService.StepResult s : result.steps()) steps.add(stepBody(s));
        out.put("steps", steps);
        return ResponseEntity.status(result.failed() ? 500 : 200).body(out);
    }

    /**
     * The setup flow's player-list fetch, once per sport per UTC day. No secret, but
     * it needs a signed-in identity (a header-less caller is refused, like every
     * other route: claude/audit-2026-09-28/01).
     *
     * <p>The "once a day" bound is the steady state, not a guarantee: after one
     * success every later call that UTC day skips, but concurrent first calls and
     * calls while Sleeper's player endpoint is failing each run a fetch
     * ({@code DailyRefreshService.players} has no single-flight). The upsert is
     * idempotent, so this costs Sleeper traffic, not correctness. Not measured.
     */
    @PostMapping("/refresh/players")
    public ResponseEntity<?> players(@RequestParam String sport,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (LeagueMembership.isAnonymous(sleeperUserId)) {
            return ResponseEntity.status(401).body(Map.of("error", "X-Sleeper-User is required"));
        }
        DailyRefreshService.StepResult r = daily.players(Sport.fromCode(sport));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", r.outcome().name());
        out.put("detail", r.detail());
        return ResponseEntity.status(r.outcome() == DailyRefreshService.Outcome.FAILED ? 500 : 200).body(out);
    }

    private static Map<String, Object> stepBody(DailyRefreshService.StepResult s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sport", s.sport().code());
        m.put("kind", s.kind());
        m.put("outcome", s.outcome().name());
        m.put("detail", s.detail());
        return m;
    }

    /** Starts a background refresh if the league is stale, and returns at once. */
    @PostMapping("/leagues/{sleeperId}/refresh")
    public ResponseEntity<?> trigger(@PathVariable String sleeperId,
                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return refresh.trigger(sleeperId)
                .map(s -> ResponseEntity.ok(body(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The same body; never starts anything. The rail polls this while a refresh runs. */
    @GetMapping("/leagues/{sleeperId}/refresh")
    public ResponseEntity<?> status(@PathVariable String sleeperId,
                                    @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        if (membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()) return ResponseEntity.notFound().build();
        return refresh.status(sleeperId)
                .map(s -> ResponseEntity.ok(body(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> body(LeagueRefreshService.Status s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", s.state().name());
        out.put("leagueSleeperId", s.leagueSleeperId());
        out.put("season", s.season());
        out.put("lastSuccessAt", s.lastSuccessAt() == null ? null : s.lastSuccessAt().toString());
        out.put("lastFailureAt", s.lastFailureAt() == null ? null : s.lastFailureAt().toString());
        List<Map<String, Object>> seasons = new ArrayList<>();
        for (LeagueRefreshService.SeasonStatus season : s.seasons()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("leagueSleeperId", season.leagueSleeperId());
            m.put("season", season.season());
            m.put("state", season.state().name());
            seasons.add(m);
        }
        out.put("seasons", seasons);
        return out;
    }
}
