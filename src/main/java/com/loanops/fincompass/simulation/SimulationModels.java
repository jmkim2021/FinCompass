package com.loanops.fincompass.simulation;

import com.loanops.fincompass.twin.TwinModels.Metric;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All rates are annual fractions; all monetary inputs and results are KRW. */
public final class SimulationModels {
    private SimulationModels() {}
    public enum RepaymentType { AMORTIZING }
    public record LoanInput(BigDecimal principal, BigDecimal annualInterestRate, int termMonths,
                            RepaymentType repaymentType) {}
    public record LoanContext(BigDecimal existingMonthlyDebtService, BigDecimal verifiedAnnualIncome) {}
    public record Payment(int month, BigDecimal openingBalance, BigDecimal interest, BigDecimal principal,
                          BigDecimal payment, BigDecimal closingBalance) {}
    public record LoanResult(LoanInput input, BigDecimal regularMonthlyPayment, BigDecimal annualDebtService,
                             BigDecimal totalRepayment, BigDecimal totalInterest, BigDecimal postLoanDebtBurden,
                             List<Payment> schedule, List<String> warnings) {
        public LoanResult { schedule = List.copyOf(schedule); warnings = List.copyOf(warnings); }
    }
    public record Adjustment(BigDecimal existingDebtRepayment, BigDecimal releasedMonthlyDebtService,
                             int delayMonths, BigDecimal explicitMonthlySavings) {
        public static Adjustment zero() { return new Adjustment(BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO); }
    }
    /** cashContribution is all closing cash, including explicitCosts. */
    public record HomeInput(UUID financialSnapshotId, BigDecimal housePrice, BigDecimal cashContribution,
                            BigDecimal mortgageAmount, BigDecimal annualInterestRate, int termMonths,
                            RepaymentType repaymentType, BigDecimal explicitCosts, Adjustment adjustment) {}
    public record HomeResult(HomeInput input, Map<String, Metric> before, Map<String, Metric> after,
                             LoanResult mortgage, List<String> warnings, List<String> limitations) {
        public HomeResult {
            before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
            after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
            warnings = List.copyOf(warnings); limitations = List.copyOf(limitations);
        }
    }
    /** Drawdowns apply to post-purchase assets; rateDelta is percentage points as a fraction. */
    public record Shock(String label, BigDecimal rateDelta, BigDecimal incomeReduction, BigDecimal expenseIncrease,
                        BigDecimal liquidDrawdown, BigDecimal riskAssetDrawdown) {}
    public record StressCase(Shock shock, HomeResult result) {}
}
