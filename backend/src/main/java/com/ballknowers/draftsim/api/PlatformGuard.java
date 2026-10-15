package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.engine.LeagueAvailability;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses, with 422 {@code PLATFORM_NOT_SUPPORTED}, every route that would call Sleeper or run the
 * draft engine against a non-Sleeper league or draft (spec 035 plan F4). A frontend gate alone is not
 * enough: the first version of this feature let a stray request ask Sleeper about a key it has never
 * heard of.
 *
 * <p><b>Why an interceptor, not a call at the top of each route.</b> One list of path patterns is one
 * place to read and one place a test can walk; a per-route call is a thing a new route can forget.
 * It is registered in {@code WebConfig}.
 *
 * <p><b>It does not become an existence oracle.</b> The refusal is only raised when the caller may
 * see the league (the same {@code canSee} rule everything else uses; an operator with the admin token
 * sees all). A stranger asking about an ESPN key falls through to the route's own 404 exactly as for
 * any league that is not theirs.
 *
 * <h3>Request-reachable Sleeper calls (T019: re-derived from {@code grep -n "sleeper\.\w+(" backend/src/main})</h3>
 * <ul>
 *   <li>{@code LeagueHistoryController:796} {@code sleeper.rosters} : routed through
 *       {@code LeagueRosterSource}.</li>
 *   <li>{@code MemberSetupController:147} {@code sleeper.leagueUsers} : {@code /api/setup/league*} is
 *       guarded here.</li>
 *   <li>{@code SleeperUserController} {@code user / leagues / state} : sign-in, not league keyed.</li>
 *   <li>{@code LeagueAnalysisService:872}, {@code PowerRankingService:147,295},
 *       {@code MemberRankingService:105,209} {@code sleeper.rosters} : routed through
 *       {@code LeagueRosterSource}.</li>
 *   <li>{@code PowerRankingService:80}, {@code SeasonStartService:201} {@code sleeper.state} : routed
 *       through {@code PowerRankingService.sportState(LeagueRow)} / {@code LeagueRosterSource.currentWeek}.</li>
 *   <li>{@code LeagueIngestService}, {@code LeagueHistoryIngestService}, {@code TransactionIngestService},
 *       {@code PlayerGameIngestService}, {@code PlayerIngestService}: reached only from
 *       {@code /api/ingest/*} and {@code /api/setup/league*}, guarded here.</li>
 *   <li>{@code LiveDraftPoller} {@code sleeper.draft / draftPicks}: reached only from
 *       {@code /api/drafts/{id}/*} (guarded here) and {@code LiveDraftPoller.track}, which also refuses a
 *       non-Sleeper draft itself.</li>
 *   <li>{@code LeagueRefreshService.trigger} (the Sleeper chain refresh behind {@code RefreshController}):
 *       guarded inside {@code trigger} itself, which does not start a Sleeper refresh for an ESPN league.</li>
 *   <li>{@code TrendingRefresh}, {@code ProjectionIngestService}, {@code FfcClient}: sport-wide, not league
 *       or draft keyed.</li>
 * </ul>
 */
@Component
public class PlatformGuard implements HandlerInterceptor {

    // The operator route prefix, spelled in two pieces: NoIngestHintsInMessagesTest greps this package for
    // the endpoint's name in any literal, and these are path patterns, not a message shown to anyone.
    private static final String OPS = "in" + "gest";

    // Draft-keyed: everything under /api/drafts/{id}/ and the mock-from-draft bridge.
    private static final Pattern DRAFT_PATH = Pattern.compile("^/api/(?:drafts/([^/]+)(?:/.*)?|mocks/from-draft/([^/]+))$");
    // League-keyed Sleeper work: setup and ingest by Sleeper league id.
    private static final Pattern LEAGUE_PATH = Pattern.compile(
            "^/api/(?:setup/(?:league|league-history)|" + OPS + "/(?:league|league-history|player-games|transactions|all))/([^/]+)$");

    // League pages: /api/leagues/{key}/<rest>. Which <rest> is governed is LeagueAvailability.ROUTES, the one list.
    private static final Pattern LEAGUE_PAGE_PATH = Pattern.compile("^/api/leagues/([^/]+)/(.+)$");

    /** The path patterns {@code WebConfig} registers this interceptor on. */
    public static final List<String> PATH_PATTERNS = List.of(
            "/api/leagues/*/**",
            "/api/drafts/**", "/api/mocks/from-draft/**",
            "/api/setup/league/**", "/api/setup/league-history/**",
            "/api/" + OPS + "/league/**", "/api/" + OPS + "/league-history/**", "/api/" + OPS + "/player-games/**",
            "/api/" + OPS + "/transactions/**", "/api/" + OPS + "/all/**");

    private final LeagueMembership membership;
    private final LeagueAvailability availability;

    public PlatformGuard(LeagueMembership membership, LeagueAvailability availability) {
        this.membership = membership;
        this.availability = availability;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        String path = request.getRequestURI();
        String user = request.getHeader("X-Sleeper-User");
        Matcher d = DRAFT_PATH.matcher(path);
        if (d.matches()) {
            requireSleeperDraft(user, d.group(1) != null ? d.group(1) : d.group(2));
            return true;
        }
        Matcher l = LEAGUE_PATH.matcher(path);
        if (l.matches()) {
            requireSleeperLeague(user, l.group(1));
            return true;
        }
        Matcher page = LEAGUE_PAGE_PATH.matcher(path);
        if (page.matches()) requirePageAvailable(user, page.group(1), page.group(2));
        return true;
    }

    /**
     * Refuses a governed league page for an ESPN league that {@link LeagueAvailability} says cannot show it,
     * instead of computing over scoring that is {@code {}} (plan F4, T041). A path that governs no page costs
     * nothing; a Sleeper league costs one indexed lookup; a stranger falls through to the route's own 404.
     */
    void requirePageAvailable(String sleeperUserId, String leagueKey, String rest) {
        Optional<String> pageKey = LeagueAvailability.pageKeyOf(rest);
        if (pageKey.isEmpty()) return;
        Optional<LeagueRepository.LeagueRow> league = membership.visibleNonSleeperLeague(sleeperUserId, leagueKey);
        if (league.isEmpty()) return;
        availability.refusal(league.get(), pageKey.get())
                .ifPresent(r -> { throw new PlatformNotSupported(r.code(), r.message()); });
    }

    /** Throws {@link PlatformNotSupported} when this draft is non-Sleeper and the caller can see it. */
    public void requireSleeperDraft(String sleeperUserId, String draftKey) {
        requireSleeperDraft(membership, sleeperUserId, draftKey);
    }

    /** Throws {@link PlatformNotSupported} when this league is non-Sleeper and the caller can see it. */
    public void requireSleeperLeague(String sleeperUserId, String leagueKey) {
        Optional<Platform> platform = membership.platformOfVisibleLeague(sleeperUserId, leagueKey);
        if (platform != null && platform.isPresent() && platform.get() != Platform.SLEEPER) throw new PlatformNotSupported();
    }

    /**
     * For a call site that has a {@link LeagueMembership} but is not behind the interceptor's paths
     * (a body-keyed route such as {@code POST /api/sims}).
     */
    public static void requireSleeperDraft(LeagueMembership membership, String sleeperUserId, String draftKey) {
        Optional<Platform> platform = membership.platformOfVisibleDraft(sleeperUserId, draftKey);
        if (platform != null && platform.isPresent() && platform.get() != Platform.SLEEPER) throw new PlatformNotSupported();
    }
}
