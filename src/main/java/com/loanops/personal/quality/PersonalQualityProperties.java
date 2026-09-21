package com.loanops.personal.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("fincompass.data-quality")
public record PersonalQualityProperties(@DefaultValue("24h") Duration balanceStaleAfter,
                                        @DefaultValue("personal-dq-v1") String policyVersion) {
    public PersonalQualityProperties {
        if(balanceStaleAfter==null||balanceStaleAfter.isNegative()||balanceStaleAfter.isZero()
                ||balanceStaleAfter.getNano()!=0||balanceStaleAfter.compareTo(Duration.ofDays(366))>0
                ||policyVersion==null||!policyVersion.matches("[A-Za-z0-9_-]{1,50}"))
            throw new IllegalArgumentException("Invalid personal data quality configuration");
    }
}
