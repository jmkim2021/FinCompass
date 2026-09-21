package com.loanops.fincompass.simulation;

import static com.loanops.fincompass.simulation.SimulationModels.*;
import static com.loanops.fincompass.twin.TwinModels.*;
import static org.assertj.core.api.Assertions.*;

import com.loanops.fincompass.twin.TwinCalculator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulationEngineTest {
    private final SimulationEngine engine = new SimulationEngine();
    private static final Instant AS_OF = Instant.parse("2026-09-21T00:00:00Z");
    private static final UUID SNAPSHOT_ID = id("snapshot");

    // Monetary oracle: independent Python Decimal precision50/HALF_EVEN schedule, not this engine.
    @Test void goldenAmortizationMatchesIndependentDecimalOracle() {
        LoanResult loan=engine.loan(loan("590000000",".045",360),context());
        assertThat(loan.regularMonthlyPayment()).isEqualByComparingTo("2989443.33");
        assertThat(loan.annualDebtService()).isEqualByComparingTo("35873319.96");
        assertThat(loan.totalRepayment()).isEqualByComparingTo("1076199597.17");
        assertThat(loan.totalInterest()).isEqualByComparingTo("486199597.17");
        assertThat(loan.postLoanDebtBurden()).isEqualByComparingTo("0.65262606");
        assertThat(loan.schedule()).hasSize(360);
        assertThat(loan.schedule().getFirst().interest()).isEqualByComparingTo("2212500");
        assertThat(loan.schedule().getFirst().closingBalance()).isEqualByComparingTo("589223056.67");
        assertThat(loan.schedule().getLast().payment()).isEqualByComparingTo("2989441.70");
        assertThat(loan.schedule().getLast().closingBalance()).isEqualByComparingTo("0");
    }
    @Test void zeroRateAndLastPaymentRoundingRepayExactly() {
        LoanResult loan=engine.loan(loan("100","0",3),new LoanContext(d("0"),d("1000")));
        assertThat(loan.regularMonthlyPayment()).isEqualByComparingTo("33.33");
        assertThat(loan.schedule().getLast().payment()).isEqualByComparingTo("33.34");
        assertThat(loan.totalRepayment()).isEqualByComparingTo("100");
        assertThat(loan.totalInterest()).isEqualByComparingTo("0");
        assertThat(loan.annualDebtService()).isEqualByComparingTo("100");
        assertThat(loan.postLoanDebtBurden()).isEqualByComparingTo(".1");
    }
    @Test void halfEvenTieAndTinyPrincipalSettleWithoutInventingMoney() {
        LoanResult tie=engine.loan(loan(".05","0",2),null);
        assertThat(tie.regularMonthlyPayment()).isEqualByComparingTo(".02");
        assertThat(tie.schedule().getLast().payment()).isEqualByComparingTo(".03");
        LoanResult tiny=engine.loan(loan(".01","0",600),null);
        assertThat(tiny.totalRepayment()).isEqualByComparingTo(".01");
        assertThat(tiny.schedule().getLast().closingBalance()).isEqualByComparingTo("0");
    }
    @Test void zeroPrincipalHasZeroPaymentsButExistingDebtBurdenRemains() {
        LoanResult zero=engine.loan(loan("0",".045",360),context());
        assertThat(zero.regularMonthlyPayment()).isEqualByComparingTo("0");
        assertThat(zero.totalInterest()).isEqualByComparingTo("0");
        assertThat(zero.postLoanDebtBurden()).isEqualByComparingTo(".10909091");
    }
    @Test void termBoundariesAndInvalidFinancialInputsAreRejected() {
        assertThat(engine.loan(loan("100",".12",1),context()).schedule().getFirst().payment()).isEqualByComparingTo("101");
        assertThat(engine.loan(loan("100","0",600),context()).schedule()).hasSize(600);
        for(LoanInput invalid:List.of(loan("1","0",0),loan("1","0",601),loan("-1","0",12),loan("1","-.1",12),loan("1","4.5",12),loan("1.001",".04",12)))
            assertThatThrownBy(()->engine.loan(invalid,context())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void everyScheduleRowAndTotalsConservePrincipal() {
        LoanResult loan=engine.loan(loan("12345678.91",".03715",257),context());
        BigDecimal principal=BigDecimal.ZERO,interest=BigDecimal.ZERO,payments=BigDecimal.ZERO,opening=d("12345678.91");
        for(Payment row:loan.schedule()) {
            assertThat(row.openingBalance()).isEqualByComparingTo(opening);
            assertThat(row.principal().add(row.interest())).isEqualByComparingTo(row.payment());
            assertThat(row.openingBalance().subtract(row.principal())).isEqualByComparingTo(row.closingBalance());
            assertThat(row.closingBalance().signum()).isGreaterThanOrEqualTo(0);
            opening=row.closingBalance();principal=principal.add(row.principal());interest=interest.add(row.interest());payments=payments.add(row.payment());
        }
        assertThat(principal).isEqualByComparingTo("12345678.91");assertThat(interest).isEqualByComparingTo(loan.totalInterest());assertThat(payments).isEqualByComparingTo(loan.totalRepayment());
    }
    @Test void missingAndZeroVerifiedIncomeNeverBecomeZeroBurden() {
        assertThat(engine.loan(loan("100","0",12),null).postLoanDebtBurden()).isNull();
        LoanResult zero=engine.loan(loan("100","0",12),new LoanContext(d("0"),d("0")));
        assertThat(zero.postLoanDebtBurden()).isNull();assertThat(zero.warnings()).anyMatch(w->w.startsWith("ZERO_VERIFIED_INCOME"));
    }
    @Test void goldenHomeBalanceSheetAndCashflowMatchIndependentOracle() {
        HomeResult home=engine.home(golden(),home("700000000","110000000","590000000",Adjustment.zero()));
        assertThat(home.before()).hasSize(12);assertThat(home.after()).hasSameSizeAs(home.before());
        metric(home.before(),"NET_WORTH","90000000");metric(home.before(),"MONTHLY_FREE_CASHFLOW","1200000");
        metric(home.after(),"TOTAL_ASSETS","710000000");metric(home.after(),"TOTAL_LIABILITIES","620000000");
        metric(home.after(),"LIQUID_ASSETS","10000000");metric(home.after(),"NET_WORTH","90000000");
        metric(home.after(),"MONTHLY_DEBT_SERVICE","3589443.33");metric(home.after(),"MONTHLY_FREE_CASHFLOW","-1789443.33");
        metric(home.after(),"EMERGENCY_FUND_MONTHS","4.54545455");metric(home.after(),"DEBT_SERVICE_BURDEN",".65262606");
        metric(home.after(),"DEBT_TO_ASSET_RATIO",".87323944");
        assertThat(home.warnings()).contains("NEGATIVE_MONTHLY_FREE_CASHFLOW");
        assertThat(home.after().get("OBSERVED_MONTHLY_OUTFLOW")).isEqualTo(home.before().get("OBSERVED_MONTHLY_OUTFLOW"));
    }
    @Test void explicitClosingCostsReduceNetWorthRatherThanInventTaxes() {
        HomeInput input=new HomeInput(SNAPSHOT_ID,d("700000000"),d("112000000"),d("590000000"),d(".045"),360,RepaymentType.AMORTIZING,d("2000000"),Adjustment.zero());
        HomeResult home=engine.home(golden(),input);
        metric(home.after(),"LIQUID_ASSETS","8000000");metric(home.after(),"NET_WORTH","88000000");metric(home.after(),"TOTAL_ASSETS","708000000");
    }
    @Test void inconsistentFundingOrLiquidityCannotGenerateScenario() {
        assertThatThrownBy(()->engine.home(golden(),home("700000000","110000000","580000000",Adjustment.zero()))).hasMessageContaining("must equal");
        assertThatThrownBy(()->engine.home(golden(),home("700000000","130000000","570000000",Adjustment.zero()))).hasMessageContaining("Insufficient liquid");
    }
    @Test void exhaustedLiquidityIsExplicitAndZeroEmergencyMonthsRemainKnown() {
        HomeResult result=engine.home(golden(),home("700000000","120000000","580000000",Adjustment.zero()));
        metric(result.after(),"LIQUID_ASSETS","0");metric(result.after(),"EMERGENCY_FUND_MONTHS","0");assertThat(result.warnings()).contains("LIQUIDITY_EXHAUSTED");
    }
    @Test void alternativeBUsesSameEngineAndMetricSchema() {
        HomeResult a=engine.home(golden(),home("700000000","110000000","590000000",Adjustment.zero()));
        HomeResult b=engine.home(golden(),home("600000000","110000000","490000000",Adjustment.zero()));
        assertThat(b.after().keySet()).isEqualTo(a.after().keySet());
        assertThat(b.mortgage().regularMonthlyPayment()).isEqualByComparingTo("2482758.02");
        metric(b.after(),"TOTAL_ASSETS","610000000");metric(b.after(),"TOTAL_LIABILITIES","520000000");metric(b.after(),"NET_WORTH","90000000");metric(b.after(),"MONTHLY_FREE_CASHFLOW","-1282758.02");
    }
    @Test void alternativeCRepaymentConsumesCashAndReleasesOnlyExplicitDebtService() {
        Adjustment prepay=new Adjustment(d("30000000"),d("600000"),0,d("0"));
        HomeResult c=engine.home(golden(),home("700000000","80000000","620000000",prepay));
        metric(c.after(),"TOTAL_ASSETS","710000000");metric(c.after(),"TOTAL_LIABILITIES","620000000");metric(c.after(),"LIQUID_ASSETS","10000000");metric(c.after(),"NET_WORTH","90000000");
        metric(c.after(),"MONTHLY_DEBT_SERVICE","3141448.92");metric(c.after(),"MONTHLY_FREE_CASHFLOW","-1341448.92");metric(c.after(),"DEBT_SERVICE_BURDEN",".57117253");
    }
    @Test void alternativeDUsesExplicitSavingsAndPreservesExistingLoan() {
        Adjustment delay=new Adjustment(d("0"),d("0"),12,d("1200000"));
        HomeResult d=engine.home(golden(),home("700000000","124400000","575600000",delay));
        metric(d.after(),"TOTAL_ASSETS","710000000");metric(d.after(),"TOTAL_LIABILITIES","605600000");metric(d.after(),"NET_WORTH","104400000");
        metric(d.after(),"MONTHLY_DEBT_SERVICE","3516480.64");metric(d.after(),"MONTHLY_FREE_CASHFLOW","-1716480.64");
        assertThat(d.warnings()).contains("DELAY_SAVINGS_ARE_EXPLICIT_ASSUMPTIONS_NOT_A_FORECAST");
    }
    @Test void impossiblePrepaymentAndDebtReleaseAreRejected() {
        for(Adjustment adjustment:List.of(new Adjustment(d("31000000"),d("600000"),0,d("0")),new Adjustment(d("0"),d("600000"),0,d("0")),new Adjustment(d("10000000"),d("600000"),0,d("0")),new Adjustment(d("30000000"),d("0"),0,d("0"))))
            assertThatThrownBy(()->engine.home(golden(),home("700000000","80000000","620000000",adjustment))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void partialRepaymentWithExplicitBoundedReleaseIsSupported() {
        HomeResult home=engine.home(golden(),home("700000000","100000000","600000000",new Adjustment(d("10000000"),d("200000"),0,d("0"))));
        metric(home.after(),"TOTAL_LIABILITIES","620000000");metric(home.after(),"LIQUID_ASSETS","10000000");
        assertThat(home.after().get("MONTHLY_DEBT_SERVICE").value()).isEqualByComparingTo(home.mortgage().regularMonthlyPayment().add(d("400000")));
    }
    @Test void rateStressAddsPercentagePointsAndMatchesIndependentOracle() {
        List<StressCase> results=engine.stress(golden(),home("700000000","110000000","590000000",Adjustment.zero()),SimulationEngine.defaultShocks());
        assertThat(results).hasSize(11);HomeResult plus2=results.get(1).result();
        assertThat(plus2.input().annualInterestRate()).isEqualByComparingTo(".065");
        assertThat(plus2.mortgage().regularMonthlyPayment()).isEqualByComparingTo("3729201.34");
        assertThat(plus2.mortgage().totalInterest()).isEqualByComparingTo("752512480.76");
        metric(plus2.after(),"MONTHLY_FREE_CASHFLOW","-2529201.34");metric(plus2.after(),"DEBT_SERVICE_BURDEN",".78712752");
    }
    @Test void incomeShockAffectsProjectionAndVerifiedIncomeButNotHistoricalObservation() {
        HomeResult result=engine.stress(golden(),home("700000000","110000000","590000000",Adjustment.zero()),List.of(shock("income","0",".20","0","0","0"))).getFirst().result();
        metric(result.after(),"OBSERVED_MONTHLY_INFLOW","4800000");metric(result.after(),"MONTHLY_FREE_CASHFLOW","-2749443.33");metric(result.after(),"DEBT_SERVICE_BURDEN",".81578258");
        assertThat(result.mortgage().postLoanDebtBurden()).isEqualByComparingTo(result.after().get("DEBT_SERVICE_BURDEN").value());
    }
    @Test void expenseShockUsesLivingAndEssentialButLeavesFixedObligationsUnchanged() {
        HomeResult result=engine.stress(golden(),home("700000000","110000000","590000000",Adjustment.zero()),List.of(shock("expense","0","0",".10","0","0"))).getFirst().result();
        metric(result.after(),"MONTHLY_FREE_CASHFLOW","-2069443.33");metric(result.after(),"EMERGENCY_FUND_MONTHS","4.13223140");
    }
    @Test void combinedStressAppliesEveryExplicitShockWithoutMutatingBase() {
        Snapshot snapshot=golden();HomeInput input=home("700000000","110000000","590000000",Adjustment.zero());HomeResult base=engine.home(snapshot,input);
        HomeResult result=engine.stress(snapshot,input,List.of(shock("combined",".02",".20",".10",".20","0"))).getFirst().result();
        metric(result.after(),"MONTHLY_FREE_CASHFLOW","-3769201.34");metric(result.after(),"LIQUID_ASSETS","8000000");metric(result.after(),"TOTAL_ASSETS","708000000");metric(result.after(),"NET_WORTH","88000000");metric(result.after(),"EMERGENCY_FUND_MONTHS","3.30578512");
        assertThat(result.before()).isEqualTo(base.before());assertThat(engine.home(snapshot,input)).isEqualTo(base);
    }
    @Test void totalIncomeLossHasNullBurdenRatherThanInfinityOrZero() {
        HomeResult result=engine.stress(golden(),home("700000000","110000000","590000000",Adjustment.zero()),List.of(shock("income-zero","0","1","0","0","0"))).getFirst().result();
        missing(result.after(),"DEBT_SERVICE_BURDEN");assertThat(result.mortgage().postLoanDebtBurden()).isNull();metric(result.after(),"MONTHLY_FREE_CASHFLOW","-6589443.33");
    }
    @Test void unidentifiedRiskAssetsStayUnknownAndNeverShockTheHouse() {
        HomeResult result=engine.stress(golden(),home("700000000","110000000","590000000",Adjustment.zero()),List.of(shock("risk","0","0","0","0",".20"))).getFirst().result();
        missing(result.after(),"TOTAL_ASSETS");missing(result.after(),"NET_WORTH");metric(result.after(),"LIQUID_ASSETS","10000000");
        assertThat(result.warnings()).anyMatch(w->w.startsWith("RISK_ASSET_COMPONENT_UNKNOWN"));
    }
    @Test void identifiedRiskComponentDrawsDownOnlyExplicitOtherAssetSubset() {
        Snapshot snapshot=withExtraFacts(golden(),List.of(fact("OTHER_ASSET","50000000"),fact("RISK_ASSET_COMPONENT","20000000")));
        HomeResult result=engine.stress(snapshot,home("700000000","110000000","590000000",Adjustment.zero()),List.of(shock("risk","0","0","0","0",".20"))).getFirst().result();
        metric(result.after(),"TOTAL_ASSETS","756000000");metric(result.after(),"NET_WORTH","136000000");metric(result.after(),"LIQUID_ASSETS","10000000");
        Snapshot excessive=withExtraFacts(golden(),List.of(fact("OTHER_ASSET","10000000"),fact("RISK_ASSET_COMPONENT","20000000")));
        assertThatThrownBy(()->engine.stress(excessive,home("700000000","110000000","590000000",Adjustment.zero()),List.of(shock("risk","0","0","0","0",".20")))).hasMessageContaining("exceeds");
    }
    @Test void missingFinancialInputsPreserveAvailabilityOrRejectUnfundableCalculation() {
        Snapshot base=golden();Input original=base.input();
        Input incomplete=new Input(original.financialUserId(),original.asOf(),original.sourceRefs(),original.facts(),original.liquidBalance(),null,original.transactions(),1,true,false,false,List.of("MISSING"));
        HomeResult result=engine.home(snapshot(incomplete),home("700000000","110000000","590000000",Adjustment.zero()));
        missing(result.after(),"MONTHLY_FREE_CASHFLOW");missing(result.after(),"DEBT_SERVICE_BURDEN");metric(result.after(),"NET_WORTH","90000000");
        Input noBalance=new Input(original.financialUserId(),original.asOf(),original.sourceRefs(),original.facts(),original.liquidBalance(),original.verifiedAnnualIncome(),original.transactions(),1,false,true,true,List.of());
        assertThatThrownBy(()->engine.home(snapshot(noBalance),home("700000000","110000000","590000000",Adjustment.zero()))).hasMessageContaining("lacks usable");
    }
    @Test void snapshotsAndSchedulesAreReproducibleAndImmutable() {
        Snapshot snapshot=golden();HomeInput input=home("700000000","110000000","590000000",Adjustment.zero());
        HomeResult first=engine.home(snapshot,input);assertThat(engine.home(snapshot,input)).isEqualTo(first);
        assertThatThrownBy(()->first.after().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->first.mortgage().schedule().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(engine.stress(snapshot,input,SimulationEngine.defaultShocks())).isEqualTo(engine.stress(snapshot,input,SimulationEngine.defaultShocks()));
    }
    @Test void malformedOrDuplicateStressInputsAreRejected() {
        HomeInput input=home("700000000","110000000","590000000",Adjustment.zero());Shock one=shock("same","0","0","0","0","0");
        assertThatThrownBy(()->engine.stress(golden(),input,List.of(one,one))).hasMessageContaining("unique");
        assertThatThrownBy(()->engine.stress(golden(),input,List.of(shock("invalid","0","1.1","0","0","0")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->engine.stress(golden(),input,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    private static LoanInput loan(String principal,String rate,int months) { return new LoanInput(d(principal),d(rate),months,RepaymentType.AMORTIZING); }
    private static LoanContext context() { return new LoanContext(d("600000"),d("66000000")); }
    private static HomeInput home(String price,String cash,String mortgage,Adjustment adjustment) { return new HomeInput(SNAPSHOT_ID,d(price),d(cash),d(mortgage),d(".045"),360,RepaymentType.AMORTIZING,d("0"),adjustment); }
    private static Shock shock(String label,String rate,String income,String expense,String liquid,String risk) { return new Shock(label,d(rate),d(income),d(expense),d(liquid),d(risk)); }
    private static void metric(Map<String,Metric> metrics,String name,String expected) { assertThat(metrics.get(name).availability()).as(name).isEqualTo(Availability.AVAILABLE);assertThat(metrics.get(name).value()).as(name).isEqualByComparingTo(expected); }
    private static void missing(Map<String,Metric> metrics,String name) { assertThat(metrics.get(name).availability()).as(name).isEqualTo(Availability.INSUFFICIENT_DATA);assertThat(metrics.get(name).value()).as(name).isNull(); }
    private static Snapshot withExtraFacts(Snapshot original,List<Fact> extra) { Input i=original.input();List<Fact> facts=new ArrayList<>(i.facts());facts.addAll(extra);return snapshot(new Input(i.financialUserId(),i.asOf(),i.sourceRefs(),facts,i.liquidBalance(),i.verifiedAnnualIncome(),i.transactions(),i.coveredMonths(),i.balanceUsable(),i.transactionsUsable(),i.incomeUsable(),i.warnings())); }
    private static Snapshot golden() {
        return snapshot(new Input(id("user"),AS_OF,List.of(),List.of(fact("EXISTING_LOAN_BALANCE","30000000"),fact("SCHEDULED_MONTHLY_DEBT_SERVICE","600000"),fact("MONTHLY_FIXED_OBLIGATION","200000"),fact("MONTHLY_ESSENTIAL_EXPENSE","2200000")),d("120000000"),d("66000000"),
            List.of(new ClassifiedTransaction(id("income"),d("4800000"),"CREDIT",Category.INCOME),new ClassifiedTransaction(id("living"),d("2800000"),"DEBIT",Category.LIVING_EXPENSE),new ClassifiedTransaction(id("debt"),d("600000"),"DEBIT",Category.DEBT_SERVICE)),1,true,true,true,List.of()));
    }
    private static Snapshot snapshot(Input input) { TwinCalculator calculator=new TwinCalculator();return new Snapshot(SNAPSHOT_ID,input.financialUserId(),AS_OF,AS_OF,"COMPLETE",TwinCalculator.RULE_VERSION,calculator.fingerprint(input),input,calculator.calculate(input)); }
    private static Fact fact(String kind,String amount) { return new Fact(id(kind),kind,d(amount),"USER_INPUT","DEMO_REPLAY",AS_OF,"KRW","demo-"+kind); }
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
}
