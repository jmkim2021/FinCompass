package com.loanops.personal.quality;

import com.loanops.personal.canonical.PersonalFinanceModels.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.loanops.personal.canonical.PersonalTypes.*;

class PersonalQualityRulesTest {
    final PersonalQualityRules rules=new PersonalQualityRules(new PersonalQualityProperties(Duration.ofHours(24),"test-v1"));
    @Test void loanPaymentMismatchUsesExplicitComponentsOnly() {
        var payment=new LoanPayment(UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH,new BigDecimal("100"),new BigDecimal("20"),new BigDecimal("119"),null,UUID.randomUUID());
        assertThat(rules.loanPaymentMismatch(payment)).hasValueSatisfying(q->{assertThat(q.rule()).isEqualTo(RuleCode.LOAN_PAYMENT_MISMATCH);assertThat(q.severity()).isEqualTo(Severity.WARNING);});
    }
    @Test void reconciledLoanPaymentHasNoMismatchRegardlessOfDecimalScale() {
        var payment=new LoanPayment(null,null,null,new BigDecimal("100.10"),new BigDecimal("20.2"),new BigDecimal("120.3000"),null,null);
        assertThat(rules.loanPaymentMismatch(payment)).isEmpty();
    }
    @Test void missingPaymentComponentsAreNotInferred() {
        assertThat(rules.loanPaymentMismatch(new LoanPayment(null,null,null,null,new BigDecimal("20"),new BigDecimal("120"),null,null))).isEmpty();
    }
    @Test void staleThresholdBoundaryIsExplicit() {
        Instant now=Instant.parse("2026-09-21T00:00:00Z");assertThat(rules.stale(now.minus(Duration.ofHours(24)),now)).isFalse();assertThat(rules.stale(now.minus(Duration.ofHours(24)).minusSeconds(1),now)).isTrue();
    }
    @Test void invalidStaleConfigurationIsRejected() {
        for(Duration d:List.of(Duration.ZERO,Duration.ofSeconds(-1),Duration.ofDays(367))) assertThatThrownBy(()->new PersonalQualityProperties(d,"v1")).isInstanceOf(IllegalArgumentException.class);
    }
}
