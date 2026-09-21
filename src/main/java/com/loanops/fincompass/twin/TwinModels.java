package com.loanops.fincompass.twin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Frozen calculation contracts. Neither collections nor historical inputs are mutable. */
public final class TwinModels {
    private TwinModels() {}
    public enum Availability { AVAILABLE, INSUFFICIENT_DATA, DEGRADED }
    public enum Category { INCOME, LIVING_EXPENSE, DEBT_SERVICE, TRANSFER, UNCLASSIFIED }
    public record Metric(BigDecimal value, Availability availability, String unit, List<String> warnings) {
        public Metric { Objects.requireNonNull(availability); warnings = List.copyOf(warnings); }
    }
    public record SourceRef(UUID recordId, UUID provenanceId, UUID validationRunId, String sourceType,
                            String sourceMode, Instant asOf, String contentHash, String purpose) {}
    public record Fact(UUID id, String kind, BigDecimal value, String sourceType, String sourceMode,
                       Instant asOf, String currency, String evidenceId) {}
    public record ClassifiedTransaction(UUID id, BigDecimal amount, String direction, Category category) {}
    public record Input(UUID financialUserId, Instant asOf, List<SourceRef> sourceRefs, List<Fact> facts,
                        BigDecimal liquidBalance, BigDecimal verifiedAnnualIncome,
                        List<ClassifiedTransaction> transactions, int coveredMonths,
                        boolean balanceUsable, boolean transactionsUsable, boolean incomeUsable,
                        List<String> warnings) {
        public Input {
            Objects.requireNonNull(financialUserId); Objects.requireNonNull(asOf);
            sourceRefs = List.copyOf(sourceRefs); facts = List.copyOf(facts);
            transactions = List.copyOf(transactions); warnings = List.copyOf(warnings);
        }
    }
    public record Result(Map<String, Metric> metrics, BigDecimal verifiedAnnualIncome,
                         BigDecimal monthlyLivingExpense, BigDecimal monthlyDebtService,
                         BigDecimal monthlyFixedObligations, BigDecimal monthlyEssentialExpense,
                         List<String> warnings, List<String> limitations) {
        public Result {
            metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
            warnings = List.copyOf(warnings); limitations = List.copyOf(limitations);
        }
    }
    public record Snapshot(UUID financialSnapshotId, UUID financialUserId, Instant asOf, Instant createdAt,
                           String status, String calculationRuleVersion, String inputFingerprint,
                           Input input, Result result) {}
}
