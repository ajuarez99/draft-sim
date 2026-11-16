package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.api.AdminGateInterceptor;
import com.ballknowers.draftsim.api.ApiTokenFilter;
import com.ballknowers.draftsim.api.LeagueLockInterceptor;
import com.ballknowers.draftsim.api.PlatformGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final CorsProperties cors;
    private final ApiSecurityProperties security;
    private final AdminAccess admin;
    private final PlatformGuard platformGuard;
    private final LeagueLockProperties leagueLock;
    private final LeagueSession leagueSession;

    public WebConfig(CorsProperties cors, ApiSecurityProperties security, AdminAccess admin,
                     PlatformGuard platformGuard, LeagueLockProperties leagueLock, LeagueSession leagueSession) {
        this.cors = cors;
        this.security = security;
        this.admin = admin;
        this.platformGuard = platformGuard;
        this.leagueLock = leagueLock;
        this.leagueSession = leagueSession;
    }

    /**
     * Every {@code /api/ingest/**} and {@code /api/admin/**} route is an operator action and needs the
     * admin token (claude/audit-2026-09-28/01). Blank ADMIN_TOKEN means these
     * all refuse. A signed-in member's own setup goes through the
     * membership-checked {@code /api/setup/**} routes instead.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminGateInterceptor(admin)).addPathPatterns("/api/ingest/**", "/api/admin/**");
        // The league API lock (spec 073 follow-up, second pass). Off by default (draftsim.league-lock.enabled). The
        // public allowlist is excluded here, so Spring's own path matching decides it, not a string compare. The
        // operator prefixes are on that allowlist, so the lock never runs for them and the order relative to the
        // admin gate above changes nothing; LeagueLockMappingsTest pins what is public and what is locked.
        registry.addInterceptor(new LeagueLockInterceptor(leagueLock, leagueSession, admin))
                .addPathPatterns("/api/**")
                .excludePathPatterns(LeagueLockInterceptor.PUBLIC_PATHS);
        // After the admin gate, so an ingest route still refuses a caller without the token first.
        registry.addInterceptor(platformGuard).addPathPatterns(PlatformGuard.PATH_PATTERNS.toArray(String[]::new));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // X-Sleeper-User: claude/user-identity-and-onboarding.md §4c --
                // apiFetch sends it on every request once signed in, so a
                // split-origin deploy (DEPLOY.md: Vercel frontend + Fly/Railway
                // backend) needs it allowed here or the browser's preflight
                // rejects every request, not just the ones that read it.
                // X-Admin-Token: the operator's key (AdminAccess). The web app stopped
                // sending it on 2026-10-05, when commissioner writes dropped it; still
                // allowed so an operator calling from a browser isn't preflight-rejected.
                .allowedHeaders("Authorization", "Content-Type", "X-Sleeper-User", "X-Admin-Token",
                        LeagueLockInterceptor.SESSION_HEADER)
                // A cross-origin browser cannot read the renewed session off a response unless it is exposed.
                .exposedHeaders(LeagueLockInterceptor.SESSION_HEADER)
                .maxAge(3600);
    }

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter() {
        FilterRegistrationBean<ApiTokenFilter> reg = new FilterRegistrationBean<>(new ApiTokenFilter(security));
        reg.addUrlPatterns("/api/*");
        // CORS here is handled by the DispatcherServlet's handler mapping, not by a
        // servlet filter, so this filter always runs first no matter what order it
        // is given -- which is why the filter exempts OPTIONS itself rather than
        // relying on ordering to protect preflights.
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 100);

        if (security.enabled()) {
            log.info("API token auth ENABLED on /api/** (/api/health stays open)");
        } else {
            log.warn("API token auth DISABLED -- every endpoint is open. "
                    + "Set API_TOKEN before exposing this beyond localhost.");
        }
        // Say which mode the admin gate is in, for the same reason as above: a
        // blank ADMIN_TOKEN fails closed, and "every ingest is 403" should be
        // one log line away from its cause, not a debugging session.
        if (admin.enabled()) {
            log.info("Admin token ENABLED (X-Admin-Token gates /api/ingest/** and commissioner actions)");
        } else {
            log.warn("Admin token DISABLED -- /api/ingest/** and commissioner actions refuse every request. "
                    + "Set ADMIN_TOKEN to enable them.");
        }
        warnIfAdminTokenIsShort();
        if (leagueLock.enabled()) {
            if (admin.enabled()) {
                log.info("League API lock ENABLED (idle {}s); the public allowlist is {}", leagueSession.idleSeconds(),
                        LeagueLockInterceptor.PUBLIC_PATHS);
            } else {
                log.warn("League API lock ENABLED but no ADMIN_TOKEN is set: the league API is locked and nobody "
                        + "can unlock it until ADMIN_TOKEN is set.");
            }
        }
        return reg;
    }

    /**
     * One WARN at startup when the admin token is configured but shorter than 32 characters (lock fix pass, CR-03):
     * league sessions and stream tickets are signed with a key derived from it, so a captured token is an offline
     * guessing oracle for it. Says nothing about the value or its length beyond that threshold.
     */
    void warnIfAdminTokenIsShort() {
        if (admin.tokenShorterThanRecommended()) {
            log.warn("ADMIN_TOKEN is shorter than {} characters. League sessions are signed with a key derived from "
                    + "it, so use a long random value (openssl rand -base64 32).", AdminProperties.RECOMMENDED_MIN_LENGTH);
        }
    }
}
