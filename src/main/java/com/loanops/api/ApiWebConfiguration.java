package com.loanops.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiWebConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final boolean demoEnabled;

    public ApiWebConfiguration(
            @Value("${loanops.api.allowed-origins:http://localhost:5173}") String allowedOrigins,
            @Value("${fincompass.demo.enabled:false}") boolean demoEnabled
    ) {
        this.allowedOrigins = StringUtils.commaDelimitedListToStringArray(allowedOrigins);
        this.demoEnabled = demoEnabled;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (demoEnabled) {
            for (String path : new String[]{"/api/v1/scenarios", "/api/v1/scenarios/**", "/api/v1/scenario-comparisons", "/api/v1/loan-product-matches", "/api/v1/loan-product-matches/**"}) {
                registry.addMapping(path).allowedOrigins(allowedOrigins).allowedMethods("GET", "POST").allowedHeaders("Content-Type").maxAge(3600);
            }
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
