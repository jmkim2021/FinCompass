package com.loanops.fincompass.twin;

import static com.loanops.fincompass.twin.TwinModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic KRW analytics. This is neither a regulated DSR assessment nor a lending decision. */
public final class TwinCalculator {
    public static final String RULE_VERSION = "financial-twin-v1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Result calculate(Input input) {
        Objects.requireNonNull(input);
        validateIdentities(input);
        List<String> warnings = new ArrayList<>(input.warnings());
        List<String> limitations = new ArrayList<>(List.of(
            "KRW_ONLY: all input amounts must be denominated in KRW",
            "VERIFIED_INCOME_SEPARATE: observed deposits are not verified annual income",
            "DEBT_SERVICE_BURDEN_IS_ANALYTICAL: not regulatory DSR or approval eligibility",
            "FIXED_OBLIGATIONS_EXCLUDE_OBSERVED_LIVING_EXPENSE: caller must classify without overlap"));
        BigDecimal liquid = input.balanceUsable() ? input.liquidBalance() : null;
        if (liquid != null && liquid.signum() < 0) {
            liquid = null;
            warnings.add("NEGATIVE_LIQUID_BALANCE_REQUIRES_LIABILITY_CLASSIFICATION");
        }
        BigDecimal otherAssets = fact(input, "OTHER_ASSET", false, warnings);
        if (input.facts().stream().noneMatch(f -> "OTHER_ASSET".equals(f.kind()))) {
            limitations.add("ASSET_SCOPE_LIQUID_ONLY: no other assets were supplied");
        }
        BigDecimal liabilities = fact(input, "EXISTING_LOAN_BALANCE", true, warnings);
        BigDecimal debtService = fact(input, "SCHEDULED_MONTHLY_DEBT_SERVICE", true, warnings);
        BigDecimal fixed = fact(input, "MONTHLY_FIXED_OBLIGATION", true, warnings);
        BigDecimal essential = fact(input, "MONTHLY_ESSENTIAL_EXPENSE", true, warnings);
        BigDecimal assets = add(liquid, otherAssets);
        BigDecimal verifiedIncome = input.incomeUsable() ? input.verifiedAnnualIncome() : null;
        if (verifiedIncome != null && verifiedIncome.signum() < 0) {
            warnings.add("INVALID_VERIFIED_ANNUAL_INCOME"); verifiedIncome = null;
        }
        boolean validTransactions = input.transactionsUsable() && input.coveredMonths() > 0;
        boolean classified = true;
        BigDecimal observedIncome = ZERO;
        BigDecimal observedOutflow = ZERO;
        BigDecimal living = ZERO;
        for (ClassifiedTransaction transaction : input.transactions()) {
            if (transaction.amount() == null || transaction.amount().signum() < 0
                || transaction.category() == null
                || !("CREDIT".equals(transaction.direction()) || "DEBIT".equals(transaction.direction()))) {
                validTransactions = false;
                warnings.add("INVALID_TRANSACTION:" + transaction.id());
                continue;
            }
            if (transaction.category() == Category.UNCLASSIFIED) classified = false;
            boolean debit = "DEBIT".equals(transaction.direction());
            if ((transaction.category() == Category.INCOME && debit)
                || ((transaction.category() == Category.LIVING_EXPENSE || transaction.category() == Category.DEBT_SERVICE) && !debit)) {
                validTransactions = false;
                warnings.add("CLASSIFICATION_DIRECTION_CONFLICT:" + transaction.id());
            }
            if (transaction.category() == Category.INCOME && !debit) observedIncome = observedIncome.add(transaction.amount());
            if (debit && transaction.category() != Category.TRANSFER) observedOutflow = observedOutflow.add(transaction.amount());
            if (debit && transaction.category() == Category.LIVING_EXPENSE) living = living.add(transaction.amount());
        }
        if (!classified) warnings.add("UNCLASSIFIED_TRANSACTIONS: income and free cashflow require explicit classification");
        if (!validTransactions) warnings.add("TRANSACTION_WINDOW_NOT_USABLE");
        if (!input.balanceUsable()) warnings.add("BALANCE_NOT_USABLE");
        if (!input.incomeUsable()) warnings.add("VERIFIED_INCOME_NOT_USABLE");
        observedIncome = validTransactions && classified ? monthly(observedIncome, input.coveredMonths()) : null;
        observedOutflow = validTransactions ? monthly(observedOutflow, input.coveredMonths()) : null;
        living = validTransactions && classified ? monthly(living, input.coveredMonths()) : null;
        BigDecimal freeCashflow = subtract(subtract(subtract(observedIncome, living), debtService), fixed);
        BigDecimal modeledOutflow = add(add(living,debtService),fixed);
        Map<String, Metric> metrics = new LinkedHashMap<>();
        money(metrics, "TOTAL_ASSETS", assets, "BALANCE_OR_OTHER_ASSETS_MISSING");
        money(metrics, "TOTAL_LIABILITIES", liabilities, "EXISTING_LOAN_BALANCE_MISSING");
        money(metrics, "LIQUID_ASSETS", liquid, "BALANCE_NOT_USABLE");
        money(metrics, "NET_WORTH", subtract(assets, liabilities), "ASSETS_OR_LIABILITIES_MISSING");
        money(metrics, "OBSERVED_MONTHLY_INFLOW", observedIncome, "COMPLETE_CLASSIFIED_TRANSACTION_WINDOW_REQUIRED");
        money(metrics, "OBSERVED_MONTHLY_OUTFLOW", observedOutflow, "COMPLETE_TRANSACTION_WINDOW_REQUIRED");
        if (observedOutflow != null && !classified) {
            metrics.put("OBSERVED_MONTHLY_OUTFLOW", new Metric(money(observedOutflow), Availability.DEGRADED, "KRW_PER_MONTH", List.of("UNCLASSIFIED_DEBITS_INCLUDED")));
        }
        money(metrics, "MONTHLY_FREE_CASHFLOW", freeCashflow, "OBSERVED_INCOME_LIVING_SCHEDULED_DEBT_AND_FIXED_OBLIGATIONS_REQUIRED");
        money(metrics, "MODELED_MONTHLY_OUTFLOW", modeledOutflow, "LIVING_SCHEDULED_DEBT_AND_FIXED_OBLIGATIONS_REQUIRED");
        money(metrics, "MODELED_FIXED_OBLIGATIONS", fixed, "MONTHLY_FIXED_OBLIGATION_MISSING");
        money(metrics, "MONTHLY_ESSENTIAL_EXPENSE", essential, "MONTHLY_ESSENTIAL_EXPENSE_MISSING");
        ratio(metrics, "EMERGENCY_FUND_MONTHS", liquid, essential, "MONTHS");
        ratio(metrics, "DEBT_SERVICE_BURDEN", debtService == null ? null : debtService.multiply(BigDecimal.valueOf(12)), verifiedIncome, "FRACTION");
        ratio(metrics, "DEBT_TO_ASSET_RATIO", liabilities, assets, "FRACTION");
        ratio(metrics, "LIQUID_ASSET_RATIO", liquid, assets, "FRACTION");
        return new Result(metrics, money(verifiedIncome), money(living), money(debtService), money(fixed),
            money(essential), warnings.stream().distinct().sorted().toList(), limitations);
    }

    /** SHA-256 of exact frozen identity and numeric inputs, insensitive to collection order and decimal scale. */
    public String fingerprint(Input input) {
        validateIdentities(input);
        StringBuilder encoded = new StringBuilder();
        fields(encoded, RULE_VERSION, input.financialUserId(), input.asOf(), decimal(input.liquidBalance()),
            decimal(input.verifiedAnnualIncome()), input.coveredMonths(), input.balanceUsable(),
            input.transactionsUsable(), input.incomeUsable());
        List<String> refs = input.sourceRefs().stream().map(r -> {
            StringBuilder item = new StringBuilder();
            fields(item, r.recordId(), r.provenanceId(), r.validationRunId(), r.sourceType(), r.sourceMode(),
                r.asOf(), r.contentHash(), r.purpose()); return item.toString();
        }).sorted().toList();
        fields(encoded, "SOURCE_REFS", refs.size()); refs.forEach(r -> fields(encoded, r));
        List<String> facts = input.facts().stream().map(f -> {
            StringBuilder item = new StringBuilder();
            fields(item, f.id(), f.kind(), decimal(f.value()), f.sourceType(), f.sourceMode(), f.asOf(),
                f.currency(), f.evidenceId()); return item.toString();
        }).sorted().toList();
        fields(encoded, "FACTS", facts.size()); facts.forEach(f -> fields(encoded, f));
        List<String> transactions = input.transactions().stream().map(t -> {
            StringBuilder item = new StringBuilder();
            fields(item, t.id(), decimal(t.amount()), t.direction(), t.category()); return item.toString();
        }).sorted().toList();
        fields(encoded, "TRANSACTIONS", transactions.size()); transactions.forEach(t -> fields(encoded, t));
        List<String> warnings = input.warnings().stream().sorted().toList();
        fields(encoded, "WARNINGS", warnings.size()); warnings.forEach(w -> fields(encoded, w));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static BigDecimal fact(Input input, String kind, boolean required, List<String> warnings) {
        List<Fact> matching = input.facts().stream().filter(f -> kind.equals(f.kind())).toList();
        if (matching.isEmpty()) { if (required) warnings.add(kind + "_MISSING"); return required ? null : ZERO; }
        BigDecimal total = ZERO;
        for (Fact fact : matching) {
            if (fact.value() == null || fact.value().signum() < 0 || !"KRW".equals(fact.currency())
                || fact.asOf() == null || fact.asOf().isAfter(input.asOf())) {
                warnings.add(kind + "_INVALID"); return null;
            }
            total = total.add(fact.value());
        }
        return total;
    }
    private static void validateIdentities(Input input) {
        Set<Object> facts = new HashSet<>();
        for (Fact fact : input.facts()) if (fact.id() == null || !facts.add(fact.id())) throw new IllegalArgumentException("Supplemental fact IDs must be unique and present");
        Set<Object> transactions = new HashSet<>();
        for (ClassifiedTransaction transaction : input.transactions()) if (transaction.id() == null || !transactions.add(transaction.id())) throw new IllegalArgumentException("Transaction IDs must be unique and present");
    }
    private static void money(Map<String, Metric> metrics, String name, BigDecimal value, String missing) {
        String unit = name.contains("MONTHLY") ? "KRW_PER_MONTH" : "KRW";
        metrics.put(name, new Metric(money(value), value == null ? Availability.INSUFFICIENT_DATA : Availability.AVAILABLE,
            unit, value == null ? List.of(missing) : List.of()));
    }
    private static void ratio(Map<String, Metric> metrics, String name, BigDecimal numerator, BigDecimal denominator, String unit) {
        boolean available = numerator != null && denominator != null && denominator.signum() > 0;
        String warning = denominator != null && denominator.signum() == 0 ? "ZERO_DENOMINATOR" : "NUMERATOR_OR_POSITIVE_DENOMINATOR_MISSING";
        metrics.put(name, new Metric(available ? numerator.divide(denominator, 8, ROUNDING) : null,
            available ? Availability.AVAILABLE : Availability.INSUFFICIENT_DATA, unit, available ? List.of() : List.of(warning)));
    }
    private static BigDecimal money(BigDecimal value) { return value == null ? null : value.setScale(2, ROUNDING); }
    private static BigDecimal monthly(BigDecimal value, int months) { return value.divide(BigDecimal.valueOf(months), 12, ROUNDING); }
    private static BigDecimal add(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.add(b); }
    private static BigDecimal subtract(BigDecimal a, BigDecimal b) { return a == null || b == null ? null : a.subtract(b); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static void fields(StringBuilder output, Object... values) {
        for (Object value : values) {
            if (value == null) output.append("-1:");
            else { String text = value.toString(); output.append(text.length()).append(':').append(text); }
        }
    }
}
