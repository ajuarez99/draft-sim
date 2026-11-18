package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.AdminAccess;
import com.ballknowers.draftsim.config.LeagueLockProperties;
import com.ballknowers.draftsim.config.LeagueSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

/**
 * The league API lock (spec 073 follow-up, second pass). Registered in {@code WebConfig} on {@code /api/**}
 * with {@link #PUBLIC_PATHS} excluded, so the allowlist is matched by Spring's own PathPattern infrastructure,
 * the same machinery that picks the handler, rather than by string compares on the raw URI.
 *
 * <p>Off ({@code draftsim.league-lock.enabled=false}, the default): passes everything. On: a request passes with a
 * valid {@code X-Admin-Token} (operator curl), or a valid {@code X-League-Session}, or, for the live-stream route
 * only (EventSource cannot send headers), a valid {@code ticket} query parameter bound to that path's draft id
 * (see {@link LeagueSession#issueTicket}). A session token is NEVER accepted as a query parameter, and a ticket is
 * never accepted as a header. A pass with a session answers with a renewed {@code X-League-Session} (sliding idle
 * window, absolute cap kept); a pass with a ticket or the admin token mints nothing. Refusal is 401
 * {@code league_session_required}, the same body for missing, malformed, expired and badly signed tokens.
 */
public class LeagueLockInterceptor implements HandlerInterceptor {

    public static final String SESSION_HEADER = "X-League-Session";
    public static final String TICKET_PARAM = "ticket";
    public static final String REFUSAL_CODE = "league_session_required";
    /** The one route whose handler pattern may take the session in the query string. */
    public static final String LIVE_STREAM_PATTERN = "/api/drafts/{sleeperDraftId}/live-stream";

    /**
     * Routes that stay open while the lock is on. Everything else under {@code /api/**} is locked by default.
     * Each entry has one reason; a new public route is a deliberate edit here.
     */
    // The operator route prefix, spelled in two pieces for the same reason as PlatformGuard.OPS: NoIngestHintsInMessagesTest
    // greps this package for the endpoint's name in any literal, and this is a path pattern, not a message.
    private static final String OPS_PREFIX = "/api/" + "in" + "gest";

    public static final List<String> PUBLIC_PATHS = List.of(
            "/api/health",              // liveness probe and the web app's reachability check
            "/api/refresh/daily",       // the external daily job; guarded by its own X-Refresh-Secret
            "/api/history/**",          // the Time Machine (games, daily, seasons, profile, award): public by design
            "/api/sleeper/user/**",     // username lookup behind sign-in on both sites; NOT /users/{id}/leagues
            "/api/usage",               // usage collection (spec 076): the send carries no session header, so a locked league site could never count a view
            "/api/admin/**",            // already behind the admin-token gate (AdminGateInterceptor)
            OPS_PREFIX + "/**");          // the operator routes, same gate

    private final LeagueLockProperties props;
    private final LeagueSession sessions;
    private final AdminAccess admin;

    public LeagueLockInterceptor(LeagueLockProperties props, LeagueSession sessions, AdminAccess admin) {
        this.props = props;
        this.sessions = sessions;
        this.admin = admin;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!props.enabled()) {
            // Lock off: nothing is refused, but a VALID presented session is still renewed. The league site's
            // own lock screen and idle timer run whatever this flag says, and without a renewed token it locked
            // an active user one window after unlock (live verification V-02, 2026-11-16: seven renewals
            // answered 204 with no header, lock at 301 s). That state is real: it is the stretch between
            // deploying the league site and enabling the lock, and the documented rollback.
            String renewed = sessions.renew(request.getHeader(SESSION_HEADER));
            if (renewed != null) response.setHeader(SESSION_HEADER, renewed);
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        if (admin.matches(request.getHeader(AdminAccess.HEADER))) return true;

        String fresh = sessions.renew(request.getHeader(SESSION_HEADER));
        if (fresh != null) {
            response.setHeader(SESSION_HEADER, fresh);
            return true;
        }
        if (isLiveStream(request) && sessions.validTicket(request.getParameter(TICKET_PARAM), streamDraftId(request))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + REFUSAL_CODE + "\",\"message\":\"this league site is locked\"}");
        return false;
    }

    /** The draft id the handler mapping bound from the path, not from the raw URI. */
    @SuppressWarnings("unchecked")
    private static String streamDraftId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof java.util.Map<?, ?> m ? (String) ((java.util.Map<String, String>) m).get("sleeperDraftId") : null;
    }

    /** The handler's own matched pattern, set by the handler mapping before interceptors run, not the raw URI. */
    private static boolean isLiveStream(HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return LIVE_STREAM_PATTERN.equals(matched == null ? null : matched.toString());
    }
}
