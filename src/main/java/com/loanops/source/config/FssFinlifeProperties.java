package com.loanops.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("loanops.sources.fss-finlife")
public record FssFinlifeProperties(
        String baseUrl,
        String path,
        String apiKey,
        String topFinGroupNo,
        int pageNo,
        Duration connectTimeout,
        Duration readTimeout
) {
}
