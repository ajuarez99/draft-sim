package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.ApiSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Shared-token gate on /api/**.
 *
 * Three routes stay open on purpose:
 *   /api/health         so a platform health check works without holding the secret
 *   /api/refresh/daily  the scheduled job (a GitHub Actions cron) can't hold the
 *                       web app's bearer token; the route has its own
 *                       X-Refresh-Secret and answers 404 when none is configured
 *                       (specs/009-auto-data-refresh research R8)
 *   OPTIONS             so the CORS preflight is never rejected before the real
 *                       request gets a chance to present its token
 *
 * With no token configured the filter is inert, which keeps local development
 * exactly as it was.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    private final ApiSecurityProperties security;

    public ApiTokenFilter(ApiSecurityProperties security) {
        this.security = security;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!security.enabled()) return true;
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        String uri = request.getRequestURI();
        return "/api/health".equals(uri) || "/api/refresh/daily".equals(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = ApiSecurityProperties.bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (!security.matches(presented)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.getWriter().write("{\"error\":\"missing or invalid bearer token\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
