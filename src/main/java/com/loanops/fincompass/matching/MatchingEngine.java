package com.loanops.fincompass.matching;

import com.loanops.fincompass.simulation.ScenarioRecords.Scenario;
import com.loanops.fincompass.simulation.SimulationEngine;
import com.loanops.fincompass.simulation.SimulationModels.*;
import com.loanops.fincompass.twin.TwinModels.Snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.loanops.fincompass.matching.MatchingModels.*;

/** Transparent hard-filter, simulation and scoring pipeline. It never decides lending eligibility. */
public final class MatchingEngine {
    public static final String SCORE_RULE_VERSION = "PRODUCT_MATCH_SCORE_V1";
    public static final String RATE_POLICY_VERSION = "PRODUCT_RATE_V1";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    private final WeightConfig weights;

    public MatchingEngine() { this(WeightConfig.defaults()); }
    public MatchingEngine(WeightConfig weights) {
        this.weights = Objects.requireNonNull(weights);
        BigDecimal sum = weights.affordability().add(weights.interestCost()).add(weights.stressResilience())
                .add(weights.preferenceFit()).add(weights.dataQuality());
        if (sum.compareTo(HUNDRED) != 0) throw new IllegalArgumentException("Score weights must total 100");
    }

    public List<Evaluation> evaluate(Request request, List<ProductRecord> products, Snapshot snapshot,
                                     Scenario homeScenario, SimulationEngine simulation) {
        return evaluate(request, products, snapshot, homeScenario, simulation, snapshot.createdAt());
    }

    public List<Evaluation> evaluate(Request request, List<ProductRecord> products, Snapshot snapshot,
                                     Scenario homeScenario, SimulationEngine simulation, Instant evaluatedAt) {
        validate(request, products, snapshot, homeScenario, evaluatedAt);
        List<Evaluation> assessed = products.stream().map(product -> assess(request, product, snapshot, homeScenario, simulation, evaluatedAt)).toList();
        List<Evaluation> scoreable = assessed.stream().filter(e -> e.status() == Status.MATCHED_FOR_REVIEW).toList();
        if (scoreable.isEmpty()) return assessed;
        BigDecimal minInterest = scoreable.stream().map(e -> e.loanResult().totalInterest()).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maxInterest = scoreable.stream().map(e -> e.loanResult().totalInterest()).max(BigDecimal::compareTo).orElseThrow();
        List<Evaluation> scored = new ArrayList<>();
        for (Evaluation value : assessed) {
            if (value.status() != Status.MATCHED_FOR_REVIEW) { scored.add(value); continue; }
            BigDecimal affordability = clamp(HUNDRED.multiply(BigDecimal.ONE.subtract(value.loanResult().regularMonthlyPayment()
                    .divide(request.maxMonthlyPayment(), 16, ROUNDING))));
            BigDecimal interest = minInterest.compareTo(maxInterest) == 0 ? HUNDRED
                    : HUNDRED.multiply(maxInterest.subtract(value.loanResult().totalInterest()))
                    .divide(maxInterest.subtract(minInterest), 8, ROUNDING);
            BigDecimal stress = stressScore(value);
            BigDecimal preference = preferenceScore(request, value.product());
            BigDecimal quality = freshness(value.product(), evaluatedAt);
            List<ScoreComponent> components = List.of(
                    component("AFFORDABILITY_FIT", affordability, weights.affordability()),
                    component("INTEREST_COST", interest, weights.interestCost()),
                    component("STRESS_RESILIENCE", stress, weights.stressResilience()),
                    component("PREFERENCE_FIT", preference, weights.preferenceFit()),
                    component("DATA_QUALITY_FRESHNESS", quality, weights.dataQuality()));
            BigDecimal total = components.stream().map(ScoreComponent::weightedScore).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, ROUNDING);
            scored.add(copy(value, new Score(total, components), null));
        }
        List<Evaluation> ranked = scored.stream().filter(e -> e.score() != null)
                .sorted(Comparator.comparing((Evaluation e) -> e.score().total()).reversed()
                        .thenComparing(Evaluation::simulationAnnualRate)
                        .thenComparingLong(e -> e.product().loanProductId())
                        .thenComparingLong(e -> e.product().productTermId())).toList();
        Map<Long,Integer> rankByTerm = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) rankByTerm.put(ranked.get(i).product().productTermId(), i + 1);
        return scored.stream().map(e -> e.score() == null ? e : copy(e, e.score(), rankByTerm.get(e.product().productTermId()))).toList();
    }

    private Evaluation assess(Request request, ProductRecord product, Snapshot snapshot, Scenario scenario,
                              SimulationEngine engine, Instant evaluatedAt) {
        List<String> warnings = new ArrayList<>();
        List<String> limitations = new ArrayList<>(List.of("REVIEW_CANDIDATE_NOT_APPROVAL", "PRODUCT_ELIGIBILITY_REQUIRES_INSTITUTION_CONFIRMATION"));
        if (!"SERVING".equals(product.qualityStatus()))
            return result(product, Status.FILTERED_OUT, null, null, null, null, warnings, List.of("NON_SERVING_OR_FAILED_QUALITY"));
        if (product.amountMin() != null && request.requiredLoanAmount().compareTo(product.amountMin()) < 0
                || product.amountMax() != null && request.requiredLoanAmount().compareTo(product.amountMax()) > 0)
            return result(product, Status.FILTERED_OUT, null, null, null, null, warnings, List.of("AMOUNT_OUTSIDE_EXPLICIT_RANGE"));
        if (product.termMin() != null && request.preferredTermYears() < product.termMin()
                || product.termMax() != null && request.preferredTermYears() > product.termMax())
            return result(product, Status.FILTERED_OUT, null, null, null, null, warnings, List.of("TERM_OUTSIDE_EXPLICIT_RANGE"));
        if (product.repaymentMethods() != null && !product.repaymentMethods().contains("AMORTIZING"))
            return result(product, Status.FILTERED_OUT, null, null, null, null, warnings, List.of("REPAYMENT_METHOD_MISMATCH"));
        BigDecimal rate = simulationRate(product, warnings);
        LoanResult loan = rate == null ? null : engine.loan(new LoanInput(request.requiredLoanAmount(), rate,
                request.preferredTermYears() * 12, RepaymentType.AMORTIZING),
                new LoanContext(snapshot.result().monthlyDebtService(), snapshot.result().verifiedAnnualIncome()));
        HomeResult home = null; StressCase plusTwo = null;
        if (scenario != null && rate != null) {
            HomeInput base = scenario.homeInput();
            BigDecimal closingCash = base.housePrice().add(base.explicitCosts() == null ? BigDecimal.ZERO : base.explicitCosts())
                    .subtract(request.requiredLoanAmount());
            if (closingCash.signum() >= 0) {
                HomeInput candidate = new HomeInput(snapshot.financialSnapshotId(), base.housePrice(), closingCash,
                        request.requiredLoanAmount(), rate, request.preferredTermYears() * 12, RepaymentType.AMORTIZING,
                        base.explicitCosts(), base.adjustment());
                home = engine.home(snapshot, candidate);
                plusTwo = engine.stress(snapshot, candidate, List.of(new Shock("RATE_PLUS_2PP", new BigDecimal(".02"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))).getFirst();
            } else limitations.add("REQUIRED_LOAN_EXCEEDS_SCENARIO_FINANCING_NEED");
        } else if (scenario == null) limitations.add("POST_SCENARIO_METRICS_REQUIRE_HOME_SCENARIO");
        List<String> missing = new ArrayList<>();
        if (product.amountMin() == null || product.amountMax() == null) missing.add("AMOUNT_RANGE_MISSING");
        if (product.termMin() == null || product.termMax() == null) missing.add("TERM_RANGE_MISSING");
        if (product.repaymentMethods() == null) missing.add("EXACT_REPAYMENT_MAPPING_MISSING");
        if (product.eligibilityText() == null || product.eligibilityText().isBlank()) missing.add("ELIGIBILITY_MISSING");
        if (rate == null) missing.add("RATE_MISSING");
        if (scenario == null || home == null || plusTwo == null) missing.add("STRESS_CONTEXT_MISSING");
        if (product.sourceObservedAt() == null) missing.add("SOURCE_OBSERVED_AT_MISSING");
        limitations.addAll(missing);
        Status status = missing.isEmpty() ? Status.MATCHED_FOR_REVIEW : Status.INSUFFICIENT_DATA;
        if (evaluatedAt.isBefore(product.productCollectedAt()) || evaluatedAt.isBefore(product.termCollectedAt()))
            warnings.add("PRODUCT_COLLECTION_AFTER_EVALUATION_TIME");
        return result(product, status, rate, loan, home, plusTwo, warnings, limitations);
    }

    private BigDecimal simulationRate(ProductRecord product, List<String> warnings) {
        BigDecimal percent;
        if (validRate(product.avgRatePercent())) { percent = product.avgRatePercent(); warnings.add("REPRESENTATIVE_AVERAGE_RATE_USED"); }
        else if (validRate(product.maxRatePercent())) { percent = product.maxRatePercent(); warnings.add("CONSERVATIVE_MAX_RATE_USED"); }
        else if (validRate(product.minRatePercent())) { percent = product.minRatePercent(); warnings.add("ONLY_MINIMUM_RATE_AVAILABLE"); }
        else return null;
        return percent.divide(HUNDRED, 16, ROUNDING).stripTrailingZeros();
    }
    private boolean validRate(BigDecimal rate) { return rate != null && rate.signum() >= 0; }
    private BigDecimal freshness(ProductRecord product, Instant evaluatedAt) {
        long days = Math.max(0, Duration.between(product.sourceObservedAt(), evaluatedAt).toDays());
        return days <= 90 ? HUNDRED : days <= 365 ? new BigDecimal("60") : new BigDecimal("20");
    }
    private BigDecimal stressScore(Evaluation value) {
        if (value.plusTwoStress() == null) return BigDecimal.ZERO;
        var metric = value.plusTwoStress().result().after().get("MONTHLY_FREE_CASHFLOW");
        return metric != null && metric.value() != null && metric.value().signum() >= 0 ? HUNDRED : BigDecimal.ZERO;
    }
    private BigDecimal preferenceScore(Request request, ProductRecord product) {
        if (request.repaymentPreferences().isEmpty()) return new BigDecimal("50");
        long met = request.repaymentPreferences().stream().filter(product.repaymentMethods()::contains).count();
        return HUNDRED.multiply(BigDecimal.valueOf(met)).divide(BigDecimal.valueOf(request.repaymentPreferences().size()), 8, ROUNDING);
    }
    private ScoreComponent component(String name, BigDecimal raw, BigDecimal weight) {
        return new ScoreComponent(name, raw.setScale(4, ROUNDING), weight, raw.multiply(weight).divide(HUNDRED, 4, ROUNDING));
    }
    private Evaluation result(ProductRecord p, Status s, BigDecimal rate, LoanResult loan, HomeResult home,
                              StressCase stress, List<String> warnings, List<String> limitations) {
        return new Evaluation(p, s, rate, loan, home, stress, null, null,
                warnings.stream().distinct().sorted().toList(), limitations.stream().distinct().sorted().toList());
    }
    private Evaluation copy(Evaluation e, Score score, Integer rank) {
        return new Evaluation(e.product(), e.status(), e.simulationAnnualRate(), e.loanResult(), e.homeResult(),
                e.plusTwoStress(), score, rank, e.warnings(), e.limitations());
    }
    private BigDecimal clamp(BigDecimal value) { return value.max(BigDecimal.ZERO).min(HUNDRED); }
    private void validate(Request request, List<ProductRecord> products, Snapshot snapshot, Scenario scenario, Instant at) {
        if (request == null || products == null || snapshot == null || at == null) throw new IllegalArgumentException("Matching inputs required");
        if (request.requiredLoanAmount() == null || request.requiredLoanAmount().signum() <= 0
                || request.maxMonthlyPayment() == null || request.maxMonthlyPayment().signum() <= 0
                || request.preferredTermYears() == null || request.preferredTermYears() < 1 || request.preferredTermYears() > 50
                || request.topK() < 1 || request.topK() > 20) throw new IllegalArgumentException("Invalid matching request");
        if (scenario != null && (!"HOME".equals(scenario.kind()) || !scenario.financialSnapshotId().equals(snapshot.financialSnapshotId())))
            throw new IllegalArgumentException("Matching scenario must be a home scenario for the same snapshot");
        Set<Long> ids = new HashSet<>();
        for (ProductRecord product : products) if (product == null || !ids.add(product.productTermId())) throw new IllegalArgumentException("Product terms must be unique");
    }
}
