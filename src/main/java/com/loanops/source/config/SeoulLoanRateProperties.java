package com.loanops.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("loanops.sources.seoul-loan-rate")
public record SeoulLoanRateProperties(
        String baseUrl,
        String serviceName,
        String apiKey,
        int startIndex,
        int endIndex,
        Duration connectTimeout,
        Duration readTimeout
) {
}
