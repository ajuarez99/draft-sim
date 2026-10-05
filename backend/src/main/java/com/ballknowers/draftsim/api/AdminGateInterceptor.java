package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.AdminAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a request unless it carries a valid {@code X-Admin-Token}. Registered
 * on {@code /api/ingest/**} in {@code WebConfig}: those routes make the server
 * crawl Sleeper into the shared database, which is an operator action, not
 * something a visitor gets to trigger. Signed-in members do their setup through
 * {@code MemberSetupController} instead, which checks league membership.
 *
 * <p>The response is a 403 with {@code code: "admin_token_required"}, so a
 * client can tell "you need the key" apart from any other 403. It is the same
 * answer for a missing token, a wrong token and a server with no
 * {@code ADMIN_TOKEN} configured -- telling those apart would only help someone
 * probing. CORS preflights pass: interceptors run for them too, and a preflight
 * never carries the header.
 */
public class AdminGateInterceptor implements HandlerInterceptor {

    public static final String REFUSAL_CODE = "admin_token_required";

    private final AdminAccess admin;

    public AdminGateInterceptor(AdminAccess admin) {
        this.admin = admin;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        if (admin.matches(request.getHeader(AdminAccess.HEADER))) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + REFUSAL_CODE + "\",\"message\":\"this route needs the admin token\"}");
        return false;
    }
}
