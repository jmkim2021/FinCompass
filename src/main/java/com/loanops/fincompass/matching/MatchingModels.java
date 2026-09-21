package com.loanops.fincompass.matching;

import com.loanops.fincompass.simulation.SimulationModels.HomeResult;
import com.loanops.fincompass.simulation.SimulationModels.LoanResult;
import com.loanops.fincompass.simulation.SimulationModels.StressCase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable matching input, exact product manifest, and explanatory score records. */
public final class MatchingModels {
    private MatchingModels() {}
    public enum Status { MATCHED_FOR_REVIEW, FILTERED_OUT, INSUFFICIENT_DATA }
    public record Request(UUID scenarioId, BigDecimal requiredLoanAmount, BigDecimal maxMonthlyPayment,
                          Integer preferredTermYears, List<String> repaymentPreferences, int topK) {
        public Request { repaymentPreferences = repaymentPreferences == null ? List.of() : List.copyOf(repaymentPreferences); }
    }
    public record ProductRecord(long loanProductId, long productTermId, long termParentProductId,
            long productPublicationRunId, long termPublicationRunId, long productSourceRunId, long termSourceRunId,
            long productRawSnapshotId, long termRawSnapshotId, String productRawHash, String termRawHash,
            String sourceInstitutionCode, String sourceProductId, String institution, String productName,
            String mortgageTypeCode, String mortgageTypeName, String repaymentTypeCode, String repaymentTypeName,
            String rateTypeCode, String rateTypeName, BigDecimal minRatePercent, BigDecimal maxRatePercent, BigDecimal avgRatePercent,
            Instant productCollectedAt, Instant termCollectedAt, Instant sourceObservedAt, String qualityStatus,
            String sourceType, String sourceMode, String productType, String purpose, BigDecimal amountMin, BigDecimal amountMax,
            Integer termMin, Integer termMax, List<String> repaymentMethods, String eligibilityText, String constraints) {
        public ProductRecord { repaymentMethods = repaymentMethods == null ? null : List.copyOf(repaymentMethods); }
    }
    public record WeightConfig(BigDecimal affordability, BigDecimal interestCost, BigDecimal stressResilience,
                               BigDecimal preferenceFit, BigDecimal dataQuality) {
        public static WeightConfig defaults() { return new WeightConfig(new BigDecimal("35"), new BigDecimal("25"), new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("10")); }
    }
    public record ScoreComponent(String name, BigDecimal rawScore, BigDecimal weightPercent, BigDecimal weightedScore) {}
    public record Score(BigDecimal total, List<ScoreComponent> components) {
        public Score { components = List.copyOf(components); }
    }
    public record Evaluation(ProductRecord product, Status status, BigDecimal simulationAnnualRate,
                             LoanResult loanResult, HomeResult homeResult, StressCase plusTwoStress, Score score, Integer rank,
                             List<String> warnings, List<String> limitations) {
        public Evaluation { warnings = List.copyOf(warnings); limitations = List.copyOf(limitations); }
    }
}
