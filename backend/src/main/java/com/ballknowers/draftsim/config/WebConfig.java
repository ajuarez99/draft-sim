package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.api.AdminGateInterceptor;
import com.ballknowers.draftsim.api.ApiTokenFilter;
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

    public WebConfig(CorsProperties cors, ApiSecurityProperties security, AdminAccess admin) {
        this.cors = cors;
        this.security = security;
        this.admin = admin;
    }

    /**
     * Every {@code /api/ingest/**} route is an operator action and needs the
     * admin token (claude/audit-2026-09-28/01). Blank ADMIN_TOKEN means these
     * all refuse. A signed-in member's own setup goes through the
     * membership-checked {@code /api/setup/**} routes instead.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminGateInterceptor(admin)).addPathPatterns("/api/ingest/**");
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
                // X-Admin-Token: the operator/commissioner key (AdminAccess). Same
                // reasoning as X-Sleeper-User: without it the preflight rejects the
                // commissioner writes the browser sends it on.
                .allowedHeaders("Authorization", "Content-Type", "X-Sleeper-User", "X-Admin-Token")
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
        return reg;
    }
}
