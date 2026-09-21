package com.loanops.personal.quality;
import java.time.*;
import java.util.*;
import com.loanops.personal.gateway.PersonalFinanceProvider.SourceBatch;
import com.loanops.personal.canonical.PersonalFinanceModels.IncomeEvidence;
import static com.loanops.personal.quality.PersonalQualityRules.*;
import static com.loanops.personal.canonical.PersonalTypes.*;

public final class IncomeQualityRules {
    private final IncomeQualityProperties policy;
    public IncomeQualityRules(IncomeQualityProperties policy) { this.policy=policy; }
    public boolean stale(Instant asOf,Instant now) { return asOf.isBefore(now.minus(policy.incomeEvidenceStaleAfter())); }
    public boolean outdated(Integer year,Instant now) { return year!=null&&year<now.atZone(ZoneOffset.UTC).getYear()-policy.maxIncomeYearAge(); }
    public Assessment assess(SourceBatch batch,List<IncomeEvidence> previous,Instant now) {
        List<Finding> issues=new ArrayList<>();ValidationState state=ValidationState.VALIDATED_COMPLETE;
        if(!batch.supported().contains(batch.capability())||"UNSUPPORTED".equals(batch.sourceStatus())) {
            state=ValidationState.PARTIAL;issues.add(issue(RuleCode.SOURCE_CAPABILITY_MISSING,"REQUESTED_CAPABILITY_UNAVAILABLE"));
        } else if(!batch.mappingValid()) {
            state=ValidationState.INVALID;issues.add(issue(RuleCode.INVALID_SOURCE_DATA,"MINIMAL_EVIDENCE_CONTRACT_REJECTED"));
        } else if(!batch.complete()||batch.incomes().isEmpty()||batch.incomes().stream().anyMatch(i->batch.capability()==Capability.INCOME?(i.annualIncome()==null||i.incomeYear()==null||i.currency()==null):(i.employmentStatus()==null||i.employmentStatus()==EmploymentStatus.UNKNOWN))) {
            state=ValidationState.PARTIAL;issues.add(issue(batch.capability()==Capability.INCOME?RuleCode.MISSING_INCOME_EVIDENCE:RuleCode.SOURCE_CAPABILITY_MISSING,"REQUIRED_EVIDENCE_MISSING"));
        } else {
            boolean invalid=batch.incomes().stream().anyMatch(i->i.evidence().asOf().isAfter(now.plusSeconds(300))||i.annualIncome()!=null&&i.annualIncome().signum()<0||i.incomeYear()!=null&&i.incomeYear()>=now.atZone(ZoneOffset.UTC).getYear());
            if(invalid) {state=ValidationState.INVALID;issues.add(issue(RuleCode.INVALID_SOURCE_DATA,"INVALID_DATE_OR_VALUE"));}
            else {
                if(batch.incomes().stream().anyMatch(i->stale(i.evidence().asOf(),now))) {state=ValidationState.STALE;issues.add(issue(RuleCode.STALE_INCOME_EVIDENCE,"EVIDENCE_AS_OF_TOO_OLD"));}
                if(batch.incomes().stream().anyMatch(i->outdated(i.incomeYear(),now))) {state=ValidationState.STALE;issues.add(issue(RuleCode.INCOME_YEAR_OUTDATED,"INCOME_YEAR_OUTSIDE_POLICY"));}
                // A bundle must have exactly one unambiguous evidence per requested capability.
                boolean conflict=batch.incomes().size()>1;
                if(!conflict&&batch.capability()==Capability.INCOME&&!previous.isEmpty()) {
                    var next=batch.incomes().getFirst();var old=previous.getFirst();
                    conflict=Objects.equals(next.incomeYear(),old.incomeYear())&&next.evidence().asOf().equals(old.asOf())&&next.annualIncome().compareTo(old.verifiedAnnualIncome())!=0;
                }
                if(!conflict&&batch.capability()==Capability.EMPLOYMENT&&!previous.isEmpty()) {
                    var next=batch.incomes().getFirst();var old=previous.getFirst();
                    conflict=next.evidence().asOf().equals(old.asOf())&&next.employmentStatus()!=old.employmentStatus();
                }
                if(conflict) {state=ValidationState.INVALID;issues.add(issue(RuleCode.CONFLICTING_INCOME_EVIDENCE,"AMBIGUOUS_EVIDENCE_REQUIRES_REVIEW"));}
            }
        }
        return new Assessment(state,issues);
    }
    public static Finding issue(RuleCode rule,String details) { return new Finding(rule,Severity.WARNING,"INCOME_EVIDENCE",details,Resolution.OPEN); }
}
