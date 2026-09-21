package com.loanops.personal.quality;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
@ConfigurationProperties("fincompass.data-quality")
public record IncomeQualityProperties(@DefaultValue("180d") Duration incomeEvidenceStaleAfter,@DefaultValue("2") int maxIncomeYearAge) {
    public IncomeQualityProperties {
        if(incomeEvidenceStaleAfter==null||incomeEvidenceStaleAfter.isNegative()||incomeEvidenceStaleAfter.isZero()||incomeEvidenceStaleAfter.getNano()!=0||incomeEvidenceStaleAfter.compareTo(Duration.ofDays(1825))>0||maxIncomeYearAge<1||maxIncomeYearAge>10) throw new IllegalArgumentException("Invalid income evidence policy");
    }
    public IncomeQualityProperties() { this(Duration.ofDays(180),2); }
}
