package com.loanops.fincompass.simulation;

import static com.loanops.fincompass.simulation.SimulationModels.*;
import static com.loanops.fincompass.twin.TwinModels.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure, versioned backend calculations. No market predictions or loan approval decisions. */
public final class SimulationEngine {
    public static final String RULE_VERSION = "FINANCIAL_SIMULATION_V1";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999999.99");

    public LoanResult loan(LoanInput input, LoanContext context) {
        if (input == null) throw new IllegalArgumentException("Loan input is required");
        amount(input.principal(), "principal"); fraction(input.annualInterestRate(), "annualInterestRate");
        if (input.termMonths() < 1 || input.termMonths() > 600) throw new IllegalArgumentException("termMonths must be between 1 and 600");
        if (input.repaymentType() != RepaymentType.AMORTIZING) throw new IllegalArgumentException("Only AMORTIZING repayment is supported");
        BigDecimal monthlyRate = input.annualInterestRate().divide(TWELVE, MC);
        BigDecimal regular;
        if (monthlyRate.signum() == 0) regular = input.principal().divide(BigDecimal.valueOf(input.termMonths()), MC);
        else {
            BigDecimal factor = ONE.add(monthlyRate, MC).pow(input.termMonths(), MC);
            regular = input.principal().multiply(monthlyRate, MC).multiply(factor, MC).divide(factor.subtract(ONE, MC), MC);
        }
        regular = money(regular);
        BigDecimal balance = money(input.principal());
        BigDecimal repayment = money(ZERO), interestTotal = money(ZERO), annual = money(ZERO);
        List<Payment> schedule = new ArrayList<>();
        for (int month = 1; month <= input.termMonths(); month++) {
            BigDecimal interest = money(balance.multiply(monthlyRate, MC));
            BigDecimal payment = month == input.termMonths() ? balance.add(interest) : regular.min(balance.add(interest));
            BigDecimal principal = payment.subtract(interest);
            if (principal.signum() < 0) throw new IllegalArgumentException("Rounded payment cannot service accrued interest");
            BigDecimal closing = money(balance.subtract(principal));
            schedule.add(new Payment(month, balance, interest, money(principal), money(payment), closing));
            repayment = repayment.add(payment); interestTotal = interestTotal.add(interest);
            if (month <= 12) annual = annual.add(payment);
            balance = closing;
        }
        List<String> warnings = new ArrayList<>();
        BigDecimal burden = null;
        if (context != null && context.existingMonthlyDebtService() != null && context.verifiedAnnualIncome() != null) {
            amount(context.existingMonthlyDebtService(), "existingMonthlyDebtService");
            amount(context.verifiedAnnualIncome(), "verifiedAnnualIncome");
            if (context.verifiedAnnualIncome().signum() > 0) burden = ratio(context.existingMonthlyDebtService().multiply(TWELVE).add(annual), context.verifiedAnnualIncome());
            else warnings.add("ZERO_VERIFIED_INCOME: debt burden unavailable");
        } else warnings.add("MISSING_DEBT_OR_VERIFIED_INCOME_CONTEXT: debt burden unavailable");
        if (schedule.getLast().payment().compareTo(regular) != 0) warnings.add("FINAL_PAYMENT_ADJUSTED_FOR_ROUNDING");
        return new LoanResult(input, regular, money(annual), money(repayment), money(interestTotal), burden, schedule, warnings);
    }

    public HomeResult home(Snapshot snapshot, HomeInput input) {
        if (snapshot == null || input == null) throw new IllegalArgumentException("Financial snapshot and home input are required");
        if (!snapshot.financialSnapshotId().equals(input.financialSnapshotId())) throw new IllegalArgumentException("Financial snapshot ID mismatch");
        amount(input.housePrice(), "housePrice"); amount(input.cashContribution(), "cashContribution"); amount(input.mortgageAmount(), "mortgageAmount");
        if (input.housePrice().signum() <= 0) throw new IllegalArgumentException("housePrice must be positive");
        BigDecimal costs = input.explicitCosts() == null ? ZERO : input.explicitCosts(); amount(costs, "explicitCosts");
        if (input.cashContribution().add(input.mortgageAmount()).compareTo(input.housePrice().add(costs)) != 0)
            throw new IllegalArgumentException("Cash contribution plus mortgage must equal house price plus explicit costs");
        Adjustment adjustment = input.adjustment() == null ? Adjustment.zero() : input.adjustment();
        amount(adjustment.existingDebtRepayment(), "existingDebtRepayment"); amount(adjustment.releasedMonthlyDebtService(), "releasedMonthlyDebtService");
        amount(adjustment.explicitMonthlySavings(), "explicitMonthlySavings");
        if (adjustment.delayMonths() < 0 || adjustment.delayMonths() > 120) throw new IllegalArgumentException("delayMonths must be between 0 and 120");
        Map<String, Metric> before = new LinkedHashMap<>(snapshot.result().metrics());
        putMoney(before, "MONTHLY_DEBT_SERVICE", snapshot.result().monthlyDebtService(), "EXISTING_DEBT_SERVICE_MISSING");
        BigDecimal assets = required(before, "TOTAL_ASSETS"), liabilities = required(before, "TOTAL_LIABILITIES"), liquid = required(before, "LIQUID_ASSETS");
        BigDecimal existingDebt = snapshot.result().monthlyDebtService();
        if (adjustment.existingDebtRepayment().compareTo(liabilities) > 0) throw new IllegalArgumentException("Existing debt repayment exceeds liabilities");
        if (adjustment.releasedMonthlyDebtService().signum() > 0) {
            if (adjustment.existingDebtRepayment().signum() == 0 || existingDebt == null || adjustment.releasedMonthlyDebtService().compareTo(existingDebt) > 0)
                throw new IllegalArgumentException("Debt service release requires bounded explicit debt repayment context");
            if (adjustment.releasedMonthlyDebtService().compareTo(existingDebt) == 0 && adjustment.existingDebtRepayment().compareTo(liabilities) < 0)
                throw new IllegalArgumentException("Partial repayment cannot remove all existing debt service");
        }
        if (liabilities.signum() > 0 && adjustment.existingDebtRepayment().compareTo(liabilities) == 0
            && (existingDebt == null || adjustment.releasedMonthlyDebtService().compareTo(existingDebt) != 0))
            throw new IllegalArgumentException("Full debt repayment requires explicit release of all known debt service");
        BigDecimal savings = adjustment.explicitMonthlySavings().multiply(BigDecimal.valueOf(adjustment.delayMonths()));
        BigDecimal availableCash = liquid.add(savings).subtract(adjustment.existingDebtRepayment());
        if (availableCash.signum() < 0 || input.cashContribution().compareTo(availableCash) > 0)
            throw new IllegalArgumentException("Insufficient liquid assets for explicit repayment and closing cash");
        BigDecimal remainingDebt = existingDebt == null ? null : existingDebt.subtract(adjustment.releasedMonthlyDebtService());
        LoanResult mortgage = loan(new LoanInput(input.mortgageAmount(), input.annualInterestRate(), input.termMonths(), input.repaymentType()),
            new LoanContext(remainingDebt, snapshot.result().verifiedAnnualIncome()));
        BigDecimal afterLiquid = availableCash.subtract(input.cashContribution());
        BigDecimal afterAssets = assets.add(savings).subtract(adjustment.existingDebtRepayment()).subtract(input.cashContribution()).add(input.housePrice());
        BigDecimal afterLiabilities = liabilities.subtract(adjustment.existingDebtRepayment()).add(input.mortgageAmount());
        BigDecimal afterDebt = add(remainingDebt, mortgage.regularMonthlyPayment());
        Map<String, Metric> after = new LinkedHashMap<>(before);
        putMoney(after, "TOTAL_ASSETS", afterAssets, "ASSETS_MISSING"); putMoney(after, "TOTAL_LIABILITIES", afterLiabilities, "LIABILITIES_MISSING");
        putMoney(after, "LIQUID_ASSETS", afterLiquid, "LIQUID_ASSETS_MISSING"); putMoney(after, "NET_WORTH", afterAssets.subtract(afterLiabilities), "BALANCE_SHEET_MISSING");
        putMoney(after, "MONTHLY_DEBT_SERVICE", afterDebt, "EXISTING_DEBT_SERVICE_MISSING");
        BigDecimal observedIncome = value(before, "OBSERVED_MONTHLY_INFLOW");
        BigDecimal free = subtract(subtract(subtract(observedIncome, snapshot.result().monthlyLivingExpense()), afterDebt), snapshot.result().monthlyFixedObligations());
        putMoney(after, "MONTHLY_FREE_CASHFLOW", free, "INCOME_LIVING_DEBT_OR_FIXED_OBLIGATIONS_MISSING");
        putRatio(after, "EMERGENCY_FUND_MONTHS", afterLiquid, snapshot.result().monthlyEssentialExpense(), "MONTHS");
        putRatio(after, "DEBT_SERVICE_BURDEN", remainingDebt == null ? null : remainingDebt.multiply(TWELVE).add(mortgage.annualDebtService()), snapshot.result().verifiedAnnualIncome(), "FRACTION");
        putRatio(after, "DEBT_TO_ASSET_RATIO", afterLiabilities, afterAssets, "FRACTION");
        putRatio(after, "LIQUID_ASSET_RATIO", afterLiquid, afterAssets, "FRACTION");
        List<String> warnings = new ArrayList<>(snapshot.result().warnings()); warnings.addAll(mortgage.warnings());
        if (input.explicitCosts() == null || costs.signum() == 0) warnings.add("ACQUISITION_COSTS_EXCLUDED_OR_EXPLICITLY_ZERO");
        if (input.adjustment() == null) warnings.add("NO_PREPURCHASE_ADJUSTMENT_ASSUMED");
        if (adjustment.existingDebtRepayment().signum() > 0 && adjustment.existingDebtRepayment().compareTo(liabilities) < 0) warnings.add("PARTIAL_DEBT_SERVICE_RELEASE_IS_EXPLICIT_USER_ASSUMPTION");
        if (adjustment.delayMonths() > 0) warnings.add("DELAY_SAVINGS_ARE_EXPLICIT_ASSUMPTIONS_NOT_A_FORECAST");
        if (free != null && free.signum() < 0) warnings.add("NEGATIVE_MONTHLY_FREE_CASHFLOW");
        if (afterLiquid.signum() == 0) warnings.add("LIQUIDITY_EXHAUSTED");
        List<String> limitations = new ArrayList<>(snapshot.result().limitations());
        limitations.addAll(List.of("SIMULATION_NOT_LOAN_APPROVAL", "CONSTANT_RATE_AND_AMORTIZING_PAYMENT_ASSUMPTION",
            "CLOSING_CASH_INCLUDES_EXPLICIT_COSTS: taxes and fees are not inferred",
            "OBSERVED_METRICS_REMAIN_HISTORICAL: after free cashflow and debt service are projections",
            "EMERGENCY_DENOMINATOR_IS_EXPLICIT_ESSENTIAL_EXPENSE: excludes debt service and fixed obligations",
            "NO_AUTOMATIC_HOME_PRICE_INCOME_OR_EXISTING_DEBT_EVOLUTION_DURING_DELAY",
            "EXISTING_HOUSING_EXPENSE_CONTINUES_UNLESS_INPUT_EXPLICITLY_CHANGES",
            "EXISTING_LOAN_REMAINING_INTEREST_NOT_INCLUDED_IN_MORTGAGE_TOTAL_INTEREST"));
        return new HomeResult(input, before, after, mortgage, sorted(warnings), sorted(limitations));
    }

    public List<StressCase> stress(Snapshot snapshot, HomeInput base, List<Shock> shocks) {
        if (shocks == null) throw new IllegalArgumentException("Stress shocks are required");
        if (shocks.isEmpty() || shocks.size() > 50) throw new IllegalArgumentException("Supply between 1 and 50 stress shocks");
        // Validate base funding independently; shocks happen after this acquisition.
        home(snapshot, base);
        List<StressCase> results = new ArrayList<>(); var labels = new HashSet<String>();
        for (Shock shock : shocks) {
            if (shock == null) throw new IllegalArgumentException("Stress shock must not be null");
            if (shock.label() == null || shock.label().isBlank() || shock.label().length() > 100 || !labels.add(shock.label())) throw new IllegalArgumentException("Stress labels must be unique and nonblank");
            fraction(shock.rateDelta(), "rateDelta"); fraction(shock.incomeReduction(), "incomeReduction"); fraction(shock.expenseIncrease(), "expenseIncrease");
            fraction(shock.liquidDrawdown(), "liquidDrawdown"); fraction(shock.riskAssetDrawdown(), "riskAssetDrawdown");
            HomeInput shockedInput = new HomeInput(base.financialSnapshotId(), base.housePrice(), base.cashContribution(), base.mortgageAmount(),
                base.annualInterestRate().add(shock.rateDelta()), base.termMonths(), base.repaymentType(), base.explicitCosts(), base.adjustment());
            HomeResult recalculated = home(snapshot, shockedInput);
            Map<String, Metric> after = new LinkedHashMap<>(recalculated.after());
            List<String> warnings = new ArrayList<>(recalculated.warnings());
            warnings.add("HYPOTHETICAL_POST_PURCHASE_SHOCK: existing loan payment is held constant");
            BigDecimal incomeFactor = ONE.subtract(shock.incomeReduction()); BigDecimal expenseFactor = ONE.add(shock.expenseIncrease());
            BigDecimal observedIncome = multiply(value(recalculated.before(), "OBSERVED_MONTHLY_INFLOW"), incomeFactor);
            BigDecimal annualIncome = multiply(snapshot.result().verifiedAnnualIncome(), incomeFactor);
            BigDecimal living = multiply(snapshot.result().monthlyLivingExpense(), expenseFactor);
            BigDecimal essential = multiply(snapshot.result().monthlyEssentialExpense(), expenseFactor);
            BigDecimal debt = value(after, "MONTHLY_DEBT_SERVICE");
            BigDecimal free = subtract(subtract(subtract(observedIncome, living), debt), snapshot.result().monthlyFixedObligations());
            putMoney(after, "MONTHLY_FREE_CASHFLOW", free, "STRESS_CASHFLOW_INPUT_MISSING");
            Adjustment adjustment = base.adjustment() == null ? Adjustment.zero() : base.adjustment();
            BigDecimal existingDebt = subtract(snapshot.result().monthlyDebtService(), adjustment.releasedMonthlyDebtService());
            putRatio(after, "DEBT_SERVICE_BURDEN", existingDebt == null ? null : existingDebt.multiply(TWELVE).add(recalculated.mortgage().annualDebtService()), annualIncome, "FRACTION");
            BigDecimal liquidBeforeShock = value(after, "LIQUID_ASSETS");
            BigDecimal liquidLoss = multiply(liquidBeforeShock, shock.liquidDrawdown());
            BigDecimal liquid = subtract(liquidBeforeShock, liquidLoss);
            BigDecimal assets = subtract(value(after, "TOTAL_ASSETS"), liquidLoss);
            if (shock.riskAssetDrawdown().signum() > 0) {
                BigDecimal riskAssets = identifiedRiskAssets(snapshot);
                if (riskAssets == null) { assets = null; warnings.add("RISK_ASSET_COMPONENT_UNKNOWN: asset shock impact unavailable"); }
                else assets = subtract(assets, riskAssets.multiply(shock.riskAssetDrawdown()));
            }
            putMoney(after, "LIQUID_ASSETS", liquid, "LIQUID_ASSETS_MISSING"); putMoney(after, "TOTAL_ASSETS", assets, "RISK_ASSET_COMPONENT_UNKNOWN");
            putMoney(after, "NET_WORTH", subtract(assets, value(after, "TOTAL_LIABILITIES")), "ASSETS_OR_LIABILITIES_MISSING");
            putRatio(after, "EMERGENCY_FUND_MONTHS", liquid, essential, "MONTHS");
            putRatio(after, "DEBT_TO_ASSET_RATIO", value(after, "TOTAL_LIABILITIES"), assets, "FRACTION");
            putRatio(after, "LIQUID_ASSET_RATIO", liquid, assets, "FRACTION");
            if (free != null && free.signum() < 0) warnings.add("NEGATIVE_MONTHLY_FREE_CASHFLOW");
            if (liquid != null && liquid.signum() == 0) warnings.add("LIQUIDITY_EXHAUSTED");
            // Keep all loan context figures consistent with the shocked verified annual income.
            LoanResult originalLoan = recalculated.mortgage();
            BigDecimal burden = value(after, "DEBT_SERVICE_BURDEN");
            List<String> loanWarnings = new ArrayList<>(originalLoan.warnings());
            if (burden == null) loanWarnings.add("STRESSED_DEBT_BURDEN_UNAVAILABLE");
            LoanResult stressedLoan = new LoanResult(originalLoan.input(), originalLoan.regularMonthlyPayment(), originalLoan.annualDebtService(),
                originalLoan.totalRepayment(), originalLoan.totalInterest(), burden, originalLoan.schedule(), sorted(loanWarnings));
            results.add(new StressCase(shock, new HomeResult(shockedInput, recalculated.before(), after, stressedLoan, sorted(warnings), recalculated.limitations())));
        }
        return List.copyOf(results);
    }

    public static List<Shock> defaultShocks() {
        return List.of(shock("RATE_PLUS_1PP", ".01", "0", "0", "0"), shock("RATE_PLUS_2PP", ".02", "0", "0", "0"), shock("RATE_PLUS_3PP", ".03", "0", "0", "0"),
            shock("INCOME_MINUS_10PCT", "0", ".10", "0", "0"), shock("INCOME_MINUS_20PCT", "0", ".20", "0", "0"), shock("INCOME_MINUS_30PCT", "0", ".30", "0", "0"),
            shock("EXPENSE_PLUS_10PCT", "0", "0", ".10", "0"), shock("EXPENSE_PLUS_20PCT", "0", "0", ".20", "0"),
            shock("LIQUID_MINUS_20PCT", "0", "0", "0", ".20"), shock("LIQUID_MINUS_30PCT", "0", "0", "0", ".30"),
            shock("COMBINED_RATE2_INCOME20_EXPENSE10_LIQUID20", ".02", ".20", ".10", ".20"));
    }
    private static Shock shock(String label, String rate, String income, String expense, String liquid) { return new Shock(label, new BigDecimal(rate), new BigDecimal(income), new BigDecimal(expense), new BigDecimal(liquid), ZERO); }
    private static BigDecimal identifiedRiskAssets(Snapshot snapshot) {
        List<Fact> risks = snapshot.input().facts().stream().filter(f -> "RISK_ASSET_COMPONENT".equals(f.kind())).toList();
        if (risks.isEmpty()) return null;
        BigDecimal total = ZERO;
        for (Fact fact : risks) {
            amount(fact.value(), "riskAssetComponent");
            if (!"KRW".equals(fact.currency()) || fact.asOf() == null || fact.asOf().isAfter(snapshot.asOf())) throw new IllegalArgumentException("Invalid risk asset evidence");
            total = total.add(fact.value());
        }
        BigDecimal other = snapshot.input().facts().stream().filter(f -> "OTHER_ASSET".equals(f.kind())).map(Fact::value).reduce(ZERO, BigDecimal::add);
        if (total.compareTo(other) > 0) throw new IllegalArgumentException("Risk asset component exceeds explicitly supplied other assets");
        return total;
    }
    private static void amount(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_MONEY) > 0 || value.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException(name + " must be a nonnegative monetary amount with at most 2 decimal places");
    }
    private static void fraction(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(ONE) > 0 || value.scale() > 16) throw new IllegalArgumentException(name + " must be a fraction between 0 and 1");
    }
    private static BigDecimal required(Map<String, Metric> metrics, String name) {
        BigDecimal result = value(metrics, name); if (result == null) throw new IllegalArgumentException("Financial snapshot lacks usable " + name); return result;
    }
    private static BigDecimal value(Map<String, Metric> metrics, String name) {
        Metric metric = metrics.get(name); return metric != null && metric.availability() == Availability.AVAILABLE ? metric.value() : null;
    }
    private static void putMoney(Map<String, Metric> metrics, String name, BigDecimal value, String warning) {
        metrics.put(name, new Metric(money(value), value == null ? Availability.INSUFFICIENT_DATA : Availability.AVAILABLE,
            name.contains("MONTHLY") ? "KRW_PER_MONTH" : "KRW", value == null ? List.of(warning) : List.of()));
    }
    private static void putRatio(Map<String, Metric> metrics, String name, BigDecimal numerator, BigDecimal denominator, String unit) {
        BigDecimal value = ratio(numerator, denominator);
        metrics.put(name, new Metric(value, value == null ? Availability.INSUFFICIENT_DATA : Availability.AVAILABLE, unit,
            value == null ? List.of(denominator != null && denominator.signum() == 0 ? "ZERO_DENOMINATOR" : "INPUT_MISSING") : List.of()));
    }
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) { return numerator == null || denominator == null || denominator.signum() <= 0 ? null : numerator.divide(denominator, 8, ROUNDING); }
    private static BigDecimal money(BigDecimal value) { return value == null ? null : value.setScale(2, ROUNDING); }
    private static BigDecimal add(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.add(b); }
    private static BigDecimal subtract(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.subtract(b); }
    private static BigDecimal multiply(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.multiply(b); }
    private static List<String> sorted(List<String> values) { return values.stream().distinct().sorted().toList(); }
}
