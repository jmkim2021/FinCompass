package com.loanops.fincompass.simulation;
import java.math.BigDecimal;
import java.util.*;
import static com.loanops.fincompass.simulation.SimulationModels.*;
/** Explicit synthetic assumptions for the offline walkthrough, never customer defaults. */
public final class ScenarioExamples {
 private ScenarioExamples(){}
 public static HomeInput golden(UUID snapshotId){return home(snapshotId,"700000000","110000000","590000000",Adjustment.zero());}
 public static List<HomeInput> alternatives(UUID snapshotId){return List.of(golden(snapshotId),home(snapshotId,"600000000","110000000","490000000",Adjustment.zero()),home(snapshotId,"700000000","80000000","620000000",new Adjustment(d("30000000"),d("600000"),0,BigDecimal.ZERO)),home(snapshotId,"700000000","124400000","575600000",new Adjustment(BigDecimal.ZERO,BigDecimal.ZERO,12,d("1200000"))));}
 private static HomeInput home(UUID id,String price,String cash,String loan,Adjustment adjustment){return new HomeInput(id,d(price),d(cash),d(loan),d("0.045"),360,RepaymentType.AMORTIZING,BigDecimal.ZERO,adjustment);}
 private static BigDecimal d(String value){return new BigDecimal(value);}
}
