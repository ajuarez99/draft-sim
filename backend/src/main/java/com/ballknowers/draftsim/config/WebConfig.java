package com.ballknowers.draftsim.config;

import com.ballknowers.draftsim.api.ApiTokenFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final CorsProperties cors;
    private final ApiSecurityProperties security;

    public WebConfig(CorsProperties cors, ApiSecurityProperties security) {
        this.cors = cors;
        this.security = security;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(cors.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
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
