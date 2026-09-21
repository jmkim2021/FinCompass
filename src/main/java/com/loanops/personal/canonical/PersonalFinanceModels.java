package com.loanops.personal.canonical;

import com.loanops.finance.AnnualInterestRate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.loanops.personal.canonical.PersonalTypes.*;

/** Service-level data only. No HTTP exposure. Sensitive record toString values are deliberately redacted. */
public final class PersonalFinanceModels {
    private PersonalFinanceModels() { }
    public record FinancialUser(UUID id, String ownerRef, Instant createdAt) {
        @Override public String toString() { return "FinancialUser[REDACTED]"; }
    }
    public record ConsentGrant(UUID id, UUID userId, ProviderCode provider, UUID connectionId, SourceMode mode,
            ConsentKind kind, Set<Capability> scopes, Instant grantedAt, Instant expiresAt, Instant revokedAt) {
        public ConsentGrant { scopes=Set.copyOf(scopes); }
        public ConsentStatus statusAt(Instant now) { return revokedAt!=null?ConsentStatus.REVOKED:!expiresAt.isAfter(now)?ConsentStatus.EXPIRED:ConsentStatus.ACTIVE; }
        @Override public String toString() { return "ConsentGrant[REDACTED]"; }
    }
    public record Provenance(UUID id, UUID validationRunId, UUID sourceSyncRunId, UUID rawSnapshotId,
            SourceType sourceType, SourceMode sourceMode, Instant asOf, Instant collectedAt, Instant normalizedAt,
            String endpoint, String responseHash, Integer rawRecordIndex) {
        @Override public String toString() { return "Provenance[REDACTED]"; }
    }
    public record FinancialAccount(UUID id, UUID userId, String sourceAccountKey, String institution,
            String accountType, String currency, UUID provenanceId) {
        @Override public String toString() { return "FinancialAccount[REDACTED]"; }
    }
    public record BalanceSnapshot(UUID id, UUID accountId, BigDecimal balance, BigDecimal availableBalance,
            String currency, Instant asOf, UUID provenanceId) {
        public BalanceSnapshot { Objects.requireNonNull(balance); Objects.requireNonNull(asOf); }
        @Override public String toString() { return "BalanceSnapshot[REDACTED]"; }
    }
    public record FinancialTransaction(UUID id, UUID accountId, Instant occurredAt, Instant postedAt,
            BigDecimal amount, Direction direction, String description, String merchant, String category,
            BigDecimal balanceAfter, String currency, String sourceTransactionKey, UUID provenanceId) {
        public FinancialTransaction {
            Objects.requireNonNull(amount); Objects.requireNonNull(direction); Objects.requireNonNull(occurredAt);
            if(amount.signum()<0) throw new IllegalArgumentException("Transaction amount must be nonnegative");
        }
        @Override public String toString() { return "FinancialTransaction[REDACTED]"; }
    }
    public record LoanAccount(UUID id, UUID userId, BigDecimal principalBalance, AnnualInterestRate annualRate,
            String repaymentType, Integer paymentDay, LocalDate nextInterestDate, String currency, UUID provenanceId) {
        @Override public String toString() { return "LoanAccount[REDACTED]"; }
    }
    public record LoanPayment(UUID id, UUID loanAccountId, Instant paidAt, BigDecimal principalPaid,
            BigDecimal interestPaid, BigDecimal totalPaid, String currency, UUID provenanceId) {
        @Override public String toString() { return "LoanPayment[REDACTED]"; }
    }
    public record CardAccount(UUID id, UUID userId, String issuer, UUID paymentAccountId, UUID provenanceId) {
        @Override public String toString() { return "CardAccount[REDACTED]"; }
    }
    public record CardBill(UUID id, UUID cardAccountId, LocalDate billingDate, BigDecimal billedAmount,
            String currency, UUID provenanceId) {
        @Override public String toString() { return "CardBill[REDACTED]"; }
    }
    public record InvestmentPosition(UUID id, UUID userId, String instrument, BigDecimal quantity,
            BigDecimal marketValue, String currency, UUID provenanceId) {
        @Override public String toString() { return "InvestmentPosition[REDACTED]"; }
    }
    public record IncomeEvidence(UUID id, UUID userId, BigDecimal verifiedAnnualIncome, Integer incomeYear,
            String employmentStatus, String evidenceType, Instant asOf, UUID provenanceId) {
        @Override public String toString() { return "IncomeEvidence[REDACTED]"; }
    }
    public record QualityIssue(UUID id, UUID validationRunId, RuleCode rule, Severity severity, Instant detectedAt,
            UUID sourceSyncRunId, String affectedEntity, String details, Resolution resolution) {
        @Override public String toString() { return "QualityIssue[rule="+rule+", severity="+severity+"]"; }
    }
    public record ValidationResult(UUID id, UUID sourceSyncRunId, UUID accountId, Capability capability,
            ValidationState state, boolean promoted, LocalDate coveredFrom, LocalDate coveredThrough,
            Instant asOf, Instant evaluatedAt, String contentHash, String policyVersion, List<QualityIssue> issues) {
        public ValidationResult { issues=List.copyOf(issues); }
        @Override public String toString() { return "ValidationResult[capability="+capability+", state="+state+", promoted="+promoted+"]"; }
    }
    public record CanonicalView(CurrentState state, FinancialAccount account, BalanceSnapshot balance, List<FinancialTransaction> transactions) {
        public CanonicalView { transactions=List.copyOf(transactions); }
        @Override public String toString() { return "CanonicalView[state="+state.effectiveState()+", count="+transactions.size()+"]"; }
    }
    public record CurrentState(ValidationResult authoritative, ValidationResult latestAttempt, Freshness freshness,
            ValidationState effectiveState, boolean completeForLatestAttempt, List<RuleCode> warnings) {
        public CurrentState { warnings=List.copyOf(warnings); }
        @Override public String toString() { return "CurrentState[state="+effectiveState+", freshness="+freshness+"]"; }
    }
}
