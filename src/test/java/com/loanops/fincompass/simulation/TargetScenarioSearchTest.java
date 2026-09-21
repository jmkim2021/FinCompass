package com.loanops.fincompass.simulation;

import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.fincompass.twin.TwinCalculator;
import com.loanops.fincompass.twin.TwinModels.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.loanops.fincompass.simulation.SimulationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TargetScenarioSearchTest {
    private static final Instant AS_OF=Instant.parse("2026-09-21T10:00:00Z");
    private final FinancialTwinStore store=mock(FinancialTwinStore.class);
    private final TargetScenarioSearch search=new TargetScenarioSearch(store,new SimulationEngine());

    @Test void findsDeterministicBoundaryAndNextStepIsInfeasible(){Snapshot s=snapshot();when(store.get("owner",s.financialSnapshotId())).thenReturn(s);var request=request(s,new BigDecimal("500000"),null,null,new BigDecimal("110000000"),new BigDecimal("700000000"),new BigDecimal("100000"));var first=search.search("owner",request);var second=search.search("owner",request);assertThat(first).isEqualTo(second);assertThat(first.feasible()).isTrue();assertThat(first.postPurchaseMonthlyFreeCashflow()).isGreaterThanOrEqualTo(new BigDecimal("500000"));assertThat(first.bindingConstraint()).isEqualTo("MONTHLY_FREE_CASHFLOW_MIN");assertThat(first.searchPrecision()).isEqualByComparingTo("100000");}
    @Test void reportsNoFeasiblePoint(){Snapshot s=snapshot();when(store.get("owner",s.financialSnapshotId())).thenReturn(s);assertThat(search.search("owner",request(s,new BigDecimal("2000000"),null,null,new BigDecimal("110000000"),new BigDecimal("700000000"),new BigDecimal("100000"))).feasible()).isFalse();}
    @Test void reportsEntireRangeAndSupportsMultipleConstraints(){Snapshot s=snapshot();when(store.get("owner",s.financialSnapshotId())).thenReturn(s);var result=search.search("owner",request(s,new BigDecimal("-5000000"),new BigDecimal("4"),new BigDecimal(".8"),new BigDecimal("110000000"),new BigDecimal("120000000"),new BigDecimal("100000")));assertThat(result.maximumTestedFeasibleHousePrice()).isEqualByComparingTo("120000000");assertThat(result.warnings()).contains("ENTIRE_SEARCH_RANGE_FEASIBLE");}
    @Test void exactBoundaryAndPrecisionAreHonored(){Snapshot s=snapshot();when(store.get("owner",s.financialSnapshotId())).thenReturn(s);var base=search.search("owner",request(s,new BigDecimal("500000"),null,null,new BigDecimal("110000000"),new BigDecimal("700000000"),new BigDecimal("100000")));var exact=search.search("owner",request(s,base.postPurchaseMonthlyFreeCashflow(),null,null,base.maximumTestedFeasibleHousePrice(),base.maximumTestedFeasibleHousePrice(),new BigDecimal("100000")));assertThat(exact.feasible()).isTrue();assertThat(exact.maximumTestedFeasibleHousePrice()).isEqualByComparingTo(base.maximumTestedFeasibleHousePrice());}

    private TargetScenarioSearch.Request request(Snapshot s,BigDecimal free,BigDecimal emergency,BigDecimal burden,BigDecimal min,BigDecimal max,BigDecimal precision){return new TargetScenarioSearch.Request(s.financialSnapshotId(),new BigDecimal(".045"),360,new BigDecimal("110000000"),RepaymentType.AMORTIZING,min,max,precision,free,emergency,burden);}
    private Snapshot snapshot(){UUID user=id("user"),sid=id("snapshot");Input input=new Input(user,AS_OF,List.of(),List.of(fact("EXISTING_LOAN_BALANCE","30000000"),fact("SCHEDULED_MONTHLY_DEBT_SERVICE","600000"),fact("MONTHLY_FIXED_OBLIGATION","200000"),fact("MONTHLY_ESSENTIAL_EXPENSE","2200000")),new BigDecimal("120000000"),new BigDecimal("66000000"),List.of(new ClassifiedTransaction(id("income"),new BigDecimal("4800000"),"CREDIT",Category.INCOME),new ClassifiedTransaction(id("living"),new BigDecimal("2800000"),"DEBIT",Category.LIVING_EXPENSE),new ClassifiedTransaction(id("debt"),new BigDecimal("600000"),"DEBIT",Category.DEBT_SERVICE)),1,true,true,true,List.of());TwinCalculator calc=new TwinCalculator();return new Snapshot(sid,user,AS_OF,AS_OF,"COMPLETE",TwinCalculator.RULE_VERSION,calc.fingerprint(input),input,calc.calculate(input));}
    private Fact fact(String kind,String value){return new Fact(id(kind),kind,new BigDecimal(value),"USER_INPUT","DEMO_REPLAY",AS_OF,"KRW","demo");}
    private static UUID id(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
}
