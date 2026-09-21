package com.loanops.personal.openbanking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.net.URI;
import static com.loanops.personal.openbanking.OpenBankingException.Category.CONFIGURATION;

@ConfigurationProperties("fincompass.open-banking")
public record OpenBankingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("DEMO_REPLAY") OpenBankingMode mode,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String authorizationUri,
        @DefaultValue("") String tokenUri,
        @DefaultValue("") String clientId,
        @JsonIgnore @DefaultValue("") String clientSecret,
        @DefaultValue("") String redirectUri,
        @DefaultValue("") String bankTranPrefix,
        @JsonIgnore @DefaultValue("") String encryptionKey,
        @DefaultValue("local-v1") String encryptionKeyId,
        @DefaultValue("180") int initialLookbackDays,
        @DefaultValue("3") int overlapDays,
        @DefaultValue("1000") int maxPages,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("1048576") int maxResponseBytes) {
    public void validate() {
        if (initialLookbackDays < 1 || initialLookbackDays > 366 || overlapDays < 1
                || overlapDays > initialLookbackDays || maxPages < 1 || maxPages > 10000
                || maxResponseBytes < 1024 || maxResponseBytes > 10485760
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) throw new OpenBankingException(CONFIGURATION);
        if (mode == OpenBankingMode.LIVE_TEST) {
            safeEndpoint(baseUrl); safeEndpoint(authorizationUri); safeEndpoint(tokenUri);
            if (clientId.isBlank() || clientSecret.isBlank() || !bankTranPrefix.matches("[A-Z0-9]{10}"))
                throw new OpenBankingException(CONFIGURATION);
            safeEndpoint(redirectUri);
        }
    }
    static void safeEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme()))))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new OpenBankingException(CONFIGURATION); }
    }
    @Override public String toString() { return "OpenBankingProperties[mode=" + mode + ", secrets=REDACTED]"; }
}
