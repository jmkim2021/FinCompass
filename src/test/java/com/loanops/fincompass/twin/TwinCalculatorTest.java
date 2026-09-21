package com.loanops.fincompass.twin;

import static com.loanops.fincompass.twin.TwinModels.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TwinCalculatorTest {
    private final TwinCalculator calculator = new TwinCalculator();
    private static final Instant AS_OF = Instant.parse("2026-09-01T00:00:00Z");

    @Test void goldenHomeBuyerHasElevenIndependentMetrics() {
        Result result = calculator.calculate(golden());
        assertThat(result.metrics()).hasSize(11);
        assertMetric(result,"TOTAL_ASSETS","120000000.00");
        assertMetric(result,"TOTAL_LIABILITIES","30000000.00");
        assertMetric(result,"LIQUID_ASSETS","120000000.00");
        assertMetric(result,"NET_WORTH","90000000.00");
        assertMetric(result,"OBSERVED_MONTHLY_INFLOW","4800000.00");
        assertMetric(result,"OBSERVED_MONTHLY_OUTFLOW","3400000.00");
        assertMetric(result,"MONTHLY_FREE_CASHFLOW","1200000.00");
        assertMetric(result,"EMERGENCY_FUND_MONTHS","54.54545455");
        assertMetric(result,"DEBT_SERVICE_BURDEN","0.10909091");
        assertMetric(result,"DEBT_TO_ASSET_RATIO","0.25000000");
        assertMetric(result,"LIQUID_ASSET_RATIO","1.00000000");
        assertThat(result.verifiedAnnualIncome()).isEqualByComparingTo("66000000");
    }
    @Test void scheduledDebtReplacesObservedDebtWithoutDoubleCounting() {
        Input input = golden();
        List<ClassifiedTransaction> transactions = new ArrayList<>(input.transactions());
        transactions.add(tx("extra-loan-payment","150000","DEBIT",Category.DEBT_SERVICE));
        Result result = calculator.calculate(copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMetric(result,"OBSERVED_MONTHLY_OUTFLOW","3550000");
        assertMetric(result,"MONTHLY_FREE_CASHFLOW","1200000");
        assertThat(result.monthlyLivingExpense()).isEqualByComparingTo("2800000");
        assertThat(result.monthlyDebtService()).isEqualByComparingTo("600000");
    }
    @Test void transfersDoNotCountAsIncomeOrLivingExpense() {
        Input input=golden(); List<ClassifiedTransaction> transactions=new ArrayList<>(input.transactions());
        transactions.add(tx("transfer-in","9000000","CREDIT",Category.TRANSFER));
        transactions.add(tx("transfer-out","9000000","DEBIT",Category.TRANSFER));
        Result result=calculator.calculate(copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMetric(result,"OBSERVED_MONTHLY_INFLOW","4800000");
        assertMetric(result,"OBSERVED_MONTHLY_OUTFLOW","3400000");
        assertMetric(result,"MONTHLY_FREE_CASHFLOW","1200000");
    }
    @Test void verifiedIncomeDoesNotSubstituteForObservedDeposits() {
        Input input=golden(); Result result=calculator.calculate(copy(input,input.facts(),input.transactions(),1,true,true,true,input.liquidBalance(),new BigDecimal("132000000")));
        assertMetric(result,"OBSERVED_MONTHLY_INFLOW","4800000");
        assertMetric(result,"MONTHLY_FREE_CASHFLOW","1200000");
        assertMetric(result,"DEBT_SERVICE_BURDEN","0.05454545");
    }
    @Test void incompleteTransactionsPreserveNetWorthButBlockCashflow() {
        Input input=golden(); Result result=calculator.calculate(copy(input,input.facts(),input.transactions(),1,true,false,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMetric(result,"NET_WORTH","90000000");
        assertMissing(result,"OBSERVED_MONTHLY_INFLOW"); assertMissing(result,"MONTHLY_FREE_CASHFLOW");
        assertThat(result.monthlyLivingExpense()).isNull();
    }
    @Test void staleBalanceDoesNotEraseIndependentDebtAndCashflowMetrics() {
        Input input=golden(); Result result=calculator.calculate(copy(input,input.facts(),input.transactions(),1,false,true,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMissing(result,"LIQUID_ASSETS"); assertMissing(result,"NET_WORTH");
        assertMetric(result,"TOTAL_LIABILITIES","30000000"); assertMetric(result,"MONTHLY_FREE_CASHFLOW","1200000");
    }
    @Test void staleIncomeBlocksBurdenButNeverObservedIncome() {
        Input input=golden(); Result result=calculator.calculate(copy(input,input.facts(),input.transactions(),1,true,true,false,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMissing(result,"DEBT_SERVICE_BURDEN"); assertMetric(result,"OBSERVED_MONTHLY_INFLOW","4800000");
        assertThat(result.verifiedAnnualIncome()).isNull();
    }
    @Test void missingSupplementalFactsStayNullInsteadOfZero() {
        Input input=golden(); Result result=calculator.calculate(copy(input,List.of(),input.transactions(),1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMissing(result,"TOTAL_LIABILITIES"); assertMissing(result,"NET_WORTH");
        assertMissing(result,"MONTHLY_FREE_CASHFLOW"); assertMissing(result,"EMERGENCY_FUND_MONTHS");
        assertMissing(result,"DEBT_SERVICE_BURDEN");
        assertThat(result.monthlyFixedObligations()).isNull();
    }
    @Test void explicitZerosAreKnownButZeroDenominatorsRemainUnavailable() {
        Input input=golden(); List<Fact> facts=input.facts().stream().map(f->new Fact(f.id(),f.kind(),BigDecimal.ZERO,f.sourceType(),f.sourceMode(),f.asOf(),f.currency(),f.evidenceId())).toList();
        Result result=calculator.calculate(copy(input,facts,input.transactions(),1,true,true,true,BigDecimal.ZERO,BigDecimal.ZERO));
        assertMetric(result,"TOTAL_LIABILITIES","0"); assertMetric(result,"MONTHLY_FREE_CASHFLOW","2000000");
        for(String metric:List.of("EMERGENCY_FUND_MONTHS","DEBT_SERVICE_BURDEN","DEBT_TO_ASSET_RATIO","LIQUID_ASSET_RATIO")) {
            assertMissing(result,metric); assertThat(result.metrics().get(metric).warnings()).contains("ZERO_DENOMINATOR");
        }
    }
    @Test void unknownClassificationDoesNotInventIncomeOrFreeCashflow() {
        Input input=golden(); List<ClassifiedTransaction> transactions=new ArrayList<>(input.transactions());
        transactions.add(tx("unknown","10000","DEBIT",Category.UNCLASSIFIED));
        Result result=calculator.calculate(copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMissing(result,"MONTHLY_FREE_CASHFLOW"); assertMissing(result,"OBSERVED_MONTHLY_INFLOW");
        assertThat(result.metrics().get("OBSERVED_MONTHLY_OUTFLOW").availability()).isEqualTo(Availability.DEGRADED);
        assertThat(result.metrics().get("OBSERVED_MONTHLY_OUTFLOW").value()).isEqualByComparingTo("3410000");
    }
    @Test void monthlyAmountsAverageOnlyExplicitCoveredMonths() {
        Input input=golden(); Result result=calculator.calculate(copy(input,input.facts(),input.transactions(),2,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome()));
        assertMetric(result,"OBSERVED_MONTHLY_INFLOW","2400000"); assertMetric(result,"OBSERVED_MONTHLY_OUTFLOW","1700000");
        assertMetric(result,"MONTHLY_FREE_CASHFLOW","200000");
        assertMissing(calculator.calculate(copy(input,input.facts(),input.transactions(),0,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome())),"MONTHLY_FREE_CASHFLOW");
    }
    @Test void frozenInputAndOutputCollectionsCannotBeMutated() {
        Input base=golden(); List<Fact> facts=new ArrayList<>(base.facts()); List<ClassifiedTransaction> txs=new ArrayList<>(base.transactions());
        Input input=copy(base,facts,txs,1,true,true,true,base.liquidBalance(),base.verifiedAnnualIncome());
        String before=calculator.fingerprint(input); facts.clear(); txs.clear();
        assertThat(calculator.fingerprint(input)).isEqualTo(before);
        assertThatThrownBy(()->input.facts().clear()).isInstanceOf(UnsupportedOperationException.class);
        Result result=calculator.calculate(input);
        assertThatThrownBy(()->result.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.limitations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void fingerprintIsStableAcrossOrderAndEquivalentDecimalScales() {
        Input input=golden(); List<Fact> facts=new ArrayList<>(input.facts()); Collections.reverse(facts);
        List<ClassifiedTransaction> transactions=new ArrayList<>(input.transactions()); Collections.reverse(transactions);
        Input reordered=copy(input,facts,transactions,1,true,true,true,new BigDecimal("120000000.000"),new BigDecimal("66000000.00"));
        assertThat(calculator.fingerprint(reordered)).isEqualTo(calculator.fingerprint(input)).hasSize(64);
        assertThat(calculator.calculate(reordered)).isEqualTo(calculator.calculate(input));
    }
    @Test void exactSourceHashAndIdentityAndFactChangesChangeFingerprint() {
        Input input=golden(); SourceRef ref=input.sourceRefs().getFirst();
        Input sourceChanged=new Input(input.financialUserId(),input.asOf(),List.of(new SourceRef(ref.recordId(),ref.provenanceId(),ref.validationRunId(),ref.sourceType(),ref.sourceMode(),ref.asOf(),"changed",ref.purpose())),input.facts(),input.liquidBalance(),input.verifiedAnnualIncome(),input.transactions(),1,true,true,true,List.of());
        assertThat(calculator.fingerprint(sourceChanged)).isNotEqualTo(calculator.fingerprint(input));
        assertThat(calculator.fingerprint(copy(input,input.facts(),input.transactions(),1,true,false,true,input.liquidBalance(),input.verifiedAnnualIncome()))).isNotEqualTo(calculator.fingerprint(input));
        List<Fact> facts=new ArrayList<>(input.facts()); facts.set(0,fact("EXISTING_LOAN_BALANCE","30000001"));
        assertThat(calculator.fingerprint(copy(input,facts,input.transactions(),1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome()))).isNotEqualTo(calculator.fingerprint(input));
    }
    @Test void invalidCurrencyAndFutureSupplementalEvidenceCannotBecomeMoney() {
        Input input=golden(); List<Fact> facts=new ArrayList<>(input.facts()); Fact debt=facts.getFirst();
        facts.set(0,new Fact(debt.id(),debt.kind(),debt.value(),debt.sourceType(),debt.sourceMode(),AS_OF,"USD",debt.evidenceId()));
        assertMissing(calculator.calculate(copy(input,facts,input.transactions(),1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome())),"TOTAL_LIABILITIES");
        facts.set(0,new Fact(debt.id(),debt.kind(),debt.value(),debt.sourceType(),debt.sourceMode(),AS_OF.plusSeconds(1),"KRW",debt.evidenceId()));
        assertMissing(calculator.calculate(copy(input,facts,input.transactions(),1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome())),"TOTAL_LIABILITIES");
    }
    @Test void duplicateTransactionIdentityIsRejectedInsteadOfCountedTwice() {
        Input input=golden(); List<ClassifiedTransaction> transactions=new ArrayList<>(input.transactions()); transactions.add(transactions.getFirst());
        assertThatThrownBy(()->calculator.calculate(copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Transaction IDs");
    }
    @Test void conflictingDirectionAndNegativeAmountsBlockTransactionMetrics() {
        Input input=golden();
        for(ClassifiedTransaction invalid:List.of(tx("negative","-1","CREDIT",Category.INCOME),tx("wrong-direction","1","CREDIT",Category.DEBT_SERVICE))) {
            List<ClassifiedTransaction> transactions=new ArrayList<>(input.transactions()); transactions.add(invalid);
            assertMissing(calculator.calculate(copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome())),"MONTHLY_FREE_CASHFLOW");
        }
    }
    @Test void halfEvenMoneyRoundingAndNegativeFreeCashflowAreExplicit() {
        Input input=golden();
        Result rounded=calculator.calculate(copy(input,input.facts(),input.transactions(),1,true,true,true,new BigDecimal("2.345"),input.verifiedAnnualIncome()));
        assertMetric(rounded,"LIQUID_ASSETS","2.34");
        List<Fact> facts=new ArrayList<>(input.facts()); facts.set(2,fact("MONTHLY_FIXED_OBLIGATION","2000000"));
        assertMetric(calculator.calculate(copy(input,facts,input.transactions(),1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome())),"MONTHLY_FREE_CASHFLOW","-600000");
    }
    @Test void invalidInputWarningOrderIsReproducibleWithSortedFingerprint() {
        Input input=golden(); List<ClassifiedTransaction> transactions=new ArrayList<>(input.transactions());
        transactions.add(tx("invalid-a","-1","DEBIT",Category.LIVING_EXPENSE));
        transactions.add(tx("invalid-b","-2","DEBIT",Category.LIVING_EXPENSE));
        Input first=copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome());
        Collections.reverse(transactions);
        Input second=copy(input,input.facts(),transactions,1,true,true,true,input.liquidBalance(),input.verifiedAnnualIncome());
        assertThat(calculator.fingerprint(first)).isEqualTo(calculator.fingerprint(second));
        assertThat(calculator.calculate(first)).isEqualTo(calculator.calculate(second));
    }
    private static void assertMetric(Result result,String name,String value) {
        assertThat(result.metrics().get(name).availability()).as(name).isEqualTo(Availability.AVAILABLE);
        assertThat(result.metrics().get(name).value()).as(name).isEqualByComparingTo(value);
    }
    private static void assertMissing(Result result,String name) {
        assertThat(result.metrics().get(name).availability()).as(name).isEqualTo(Availability.INSUFFICIENT_DATA);
        assertThat(result.metrics().get(name).value()).as(name).isNull();
    }
    private static Input copy(Input original,List<Fact> facts,List<ClassifiedTransaction> transactions,int months,boolean balance,boolean txs,boolean income,BigDecimal liquid,BigDecimal annual) {
        return new Input(original.financialUserId(),original.asOf(),original.sourceRefs(),facts,liquid,annual,transactions,months,balance,txs,income,original.warnings());
    }
    private static Input golden() {
        return new Input(id("user"),AS_OF,List.of(new SourceRef(id("balance"),id("provenance"),id("validation"),"OPEN_BANKING_TEST","DEMO_REPLAY",AS_OF,"sha256-source-content","BALANCE")),
            List.of(fact("EXISTING_LOAN_BALANCE","30000000"),fact("SCHEDULED_MONTHLY_DEBT_SERVICE","600000"),fact("MONTHLY_FIXED_OBLIGATION","200000"),fact("MONTHLY_ESSENTIAL_EXPENSE","2200000")),
            new BigDecimal("120000000"),new BigDecimal("66000000"),
            List.of(tx("salary","4800000","CREDIT",Category.INCOME),tx("living","2800000","DEBIT",Category.LIVING_EXPENSE),tx("debt","600000","DEBIT",Category.DEBT_SERVICE)),
            1,true,true,true,List.of());
    }
    private static Fact fact(String kind,String value) { return new Fact(id(kind),kind,new BigDecimal(value),"USER_INPUT","DEMO_REPLAY",AS_OF,"KRW","demo-"+kind); }
    private static ClassifiedTransaction tx(String id,String amount,String direction,Category category) { return new ClassifiedTransaction(id(id),new BigDecimal(amount),direction,category); }
    private static UUID id(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)); }
}
