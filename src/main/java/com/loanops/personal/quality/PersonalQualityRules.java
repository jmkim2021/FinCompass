package com.loanops.personal.quality;

import java.time.*;
import java.util.*;
import com.loanops.personal.gateway.PersonalFinanceProvider.SourceBatch;
import com.loanops.personal.canonical.PersonalFinanceModels.LoanPayment;
import static com.loanops.personal.canonical.PersonalTypes.*;

public final class PersonalQualityRules {
    public record Finding(RuleCode rule, Severity severity, String affectedEntity, String details, Resolution resolution) { }
    public record Assessment(ValidationState state, List<Finding> findings) { public Assessment { findings=List.copyOf(findings); } }
    private final PersonalQualityProperties config;
    public PersonalQualityRules(PersonalQualityProperties config) { this.config=config; }
    public Assessment assess(SourceBatch batch, Instant now, boolean hasVerifiedIncome) {
        List<Finding> findings=new ArrayList<>();
        ValidationState state=ValidationState.VALIDATED_COMPLETE;
        if(!batch.supported().contains(batch.capability())||"UNSUPPORTED".equals(batch.sourceStatus())) {
            state=ValidationState.PARTIAL;
            if(batch.supported().contains(batch.capability())) findings.add(new Finding(RuleCode.SOURCE_CAPABILITY_MISSING,Severity.WARNING,
                    batch.capability().name(),"SOURCE_REPORTED_CAPABILITY_UNAVAILABLE",Resolution.OPEN));
        }
        else if(!batch.complete()||!"SUCCESS".equals(batch.sourceStatus())) {
            state=ValidationState.PARTIAL;
            findings.add(new Finding(batch.capability()==Capability.TRANSACTIONS?RuleCode.PAGINATION_INCOMPLETE:RuleCode.SOURCE_SYNC_FAILED,
                    Severity.ERROR,batch.capability().name(),"SOURCE_REQUEST_NOT_COMPLETE",Resolution.OPEN));
        } else if(!batch.mappingValid()) {
            state=ValidationState.INVALID;
            findings.add(new Finding(RuleCode.INVALID_SOURCE_DATA,Severity.ERROR,batch.capability().name(),"CANONICAL_MAPPING_REJECTED",Resolution.OPEN));
        } else if(batch.balance()!=null&&stale(batch.balance().evidence().asOf(),now)) {
            state=ValidationState.STALE;
            findings.add(new Finding(RuleCode.STALE_BALANCE,Severity.WARNING,"BALANCE","BALANCE_AGE_EXCEEDS_CONFIGURED_THRESHOLD",Resolution.OPEN));
        }
        if(batch.duplicateCount()>0) findings.add(new Finding(RuleCode.DUPLICATE_TRANSACTION,Severity.INFO,"TRANSACTIONS",
                "SOURCE_DUPLICATE_COUNT="+batch.duplicateCount(),Resolution.RESOLVED));
        if(!hasVerifiedIncome) findings.add(new Finding(RuleCode.MISSING_INCOME_EVIDENCE,Severity.WARNING,"INCOME","NO_VERIFIED_ANNUAL_INCOME_EVIDENCE",Resolution.OPEN));
        for(Capability c:Capability.values()) if(c!=Capability.EMPLOYMENT&&!batch.supported().contains(c)) findings.add(new Finding(RuleCode.SOURCE_CAPABILITY_MISSING,
                Severity.WARNING,c.name(),"PROVIDER_CAPABILITY_UNSUPPORTED",Resolution.OPEN));
        return new Assessment(state,findings);
    }
    public boolean stale(Instant asOf,Instant now) { return asOf.isBefore(now.minus(config.balanceStaleAfter())); }
    /** Only compares explicitly supplied payment components. Never infers loan repayments from transaction descriptions. */
    public Optional<Finding> loanPaymentMismatch(LoanPayment payment) {
        if(payment.principalPaid()==null||payment.interestPaid()==null||payment.totalPaid()==null) return Optional.empty();
        boolean mismatch=payment.principalPaid().signum()<0||payment.interestPaid().signum()<0||payment.totalPaid().signum()<0
                ||payment.principalPaid().add(payment.interestPaid()).compareTo(payment.totalPaid())!=0;
        return mismatch?Optional.of(new Finding(RuleCode.LOAN_PAYMENT_MISMATCH,Severity.WARNING,"LOAN_PAYMENT","EXPLICIT_PAYMENT_COMPONENTS_DO_NOT_RECONCILE",Resolution.OPEN)):Optional.empty();
    }
}
