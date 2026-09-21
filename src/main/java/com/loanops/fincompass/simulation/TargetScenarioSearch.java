package com.loanops.fincompass.simulation;

import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.fincompass.twin.TwinModels.Metric;
import com.loanops.fincompass.twin.TwinModels.Snapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.loanops.fincompass.simulation.SimulationModels.*;

/** Deterministic bounded search over house price. Results are simulation boundaries, never affordability advice. */
public final class TargetScenarioSearch {
    public static final String RULE_VERSION = "TARGET_HOME_PRICE_SEARCH_V1";
    private final FinancialTwinStore twins;
    private final SimulationEngine simulations;
    public TargetScenarioSearch(FinancialTwinStore twins, SimulationEngine simulations){this.twins=twins;this.simulations=simulations;}

    public Result search(String owner, Request request) {
        validate(request);Snapshot snapshot=twins.get(owner,request.financialSnapshotId());
        long steps=request.maxHousePrice().subtract(request.minHousePrice()).divideToIntegralValue(request.searchPrecision()).longValueExact();
        long low=0,high=steps,best=-1,iterations=0;HomeResult bestResult=null;
        while(low<=high){long mid=low+(high-low)/2;BigDecimal price=price(request,mid);HomeResult candidate=simulate(snapshot,request,price);iterations++;
            if(feasible(candidate,request)){best=mid;bestResult=candidate;low=mid+1;}else high=mid-1;}
        List<String>warnings=new ArrayList<>(List.of("SIMULATION_BOUNDARY_NOT_OFFICIAL_AFFORDABILITY","MONOTONICITY_ASSUMPTION_FIXED_CASH_RATE_TERM_AND_REPAYMENT","SEARCH_RESULT_ROUNDED_DOWN_TO_PRECISION"));
        if(best<0)return new Result(false,null,null,null,null,null,null,"NO_FEASIBLE_POINT",iterations,request.searchPrecision(),RULE_VERSION,warnings);
        if(best==steps)warnings.add("ENTIRE_SEARCH_RANGE_FEASIBLE");BigDecimal boundary=price(request,best);
        return new Result(true,boundary,bestResult.input().mortgageAmount(),bestResult.mortgage().regularMonthlyPayment(),value(bestResult,"MONTHLY_FREE_CASHFLOW"),value(bestResult,"EMERGENCY_FUND_MONTHS"),value(bestResult,"DEBT_SERVICE_BURDEN"),binding(bestResult,request),iterations,request.searchPrecision(),RULE_VERSION,warnings);
    }
    private HomeResult simulate(Snapshot snapshot,Request request,BigDecimal price){BigDecimal mortgage=price.subtract(request.cashContribution());return simulations.home(snapshot,new HomeInput(snapshot.financialSnapshotId(),price,request.cashContribution(),mortgage,request.annualInterestRate(),request.termMonths(),request.repaymentType(),BigDecimal.ZERO,Adjustment.zero()));}
    private boolean feasible(HomeResult r,Request q){return atLeast(r,"MONTHLY_FREE_CASHFLOW",q.minMonthlyFreeCashflow())&&atLeast(r,"EMERGENCY_FUND_MONTHS",q.minEmergencyFundMonths())&&atMost(r,"DEBT_SERVICE_BURDEN",q.maxDebtServiceBurden());}
    private String binding(HomeResult r,Request q){BigDecimal nearest=null;String name="SEARCH_MAX_BOUND";
        if(q.minMonthlyFreeCashflow()!=null){nearest=distance(value(r,"MONTHLY_FREE_CASHFLOW"),q.minMonthlyFreeCashflow());name="MONTHLY_FREE_CASHFLOW_MIN";}
        if(q.minEmergencyFundMonths()!=null){BigDecimal d=distance(value(r,"EMERGENCY_FUND_MONTHS"),q.minEmergencyFundMonths());if(nearest==null||d.compareTo(nearest)<0){nearest=d;name="EMERGENCY_FUND_MONTHS_MIN";}}
        if(q.maxDebtServiceBurden()!=null){BigDecimal d=distance(value(r,"DEBT_SERVICE_BURDEN"),q.maxDebtServiceBurden());if(nearest==null||d.compareTo(nearest)<0)name="DEBT_SERVICE_BURDEN_MAX";}return name;}
    private static BigDecimal price(Request r,long step){return r.minHousePrice().add(r.searchPrecision().multiply(BigDecimal.valueOf(step)));}
    private static BigDecimal distance(BigDecimal a,BigDecimal b){return a==null?new BigDecimal("999999999999999999"):a.subtract(b).abs();}
    private static BigDecimal value(HomeResult r,String name){Metric m=r.after().get(name);return m==null?null:m.value();}
    private static boolean atLeast(HomeResult r,String name,BigDecimal min){return min==null||(value(r,name)!=null&&value(r,name).compareTo(min)>=0);}
    private static boolean atMost(HomeResult r,String name,BigDecimal max){return max==null||(value(r,name)!=null&&value(r,name).compareTo(max)<=0);}
    private static void validate(Request r){
        if(r==null||r.financialSnapshotId()==null||r.annualInterestRate()==null||r.cashContribution()==null||r.minHousePrice()==null||r.maxHousePrice()==null||r.searchPrecision()==null)throw new IllegalArgumentException("Target search inputs required");
        if(r.termMonths()<1||r.termMonths()>600||r.repaymentType()!=RepaymentType.AMORTIZING)throw new IllegalArgumentException("Unsupported target search term or repayment type");
        if(r.annualInterestRate().signum()<0||r.annualInterestRate().compareTo(BigDecimal.ONE)>0||r.cashContribution().signum()<0||r.minHousePrice().compareTo(r.cashContribution())<0||r.maxHousePrice().compareTo(r.minHousePrice())<0||r.searchPrecision().signum()<=0)throw new IllegalArgumentException("Invalid target search range or financing inputs");
        if(r.maxHousePrice().subtract(r.minHousePrice()).divideToIntegralValue(r.searchPrecision()).compareTo(new BigDecimal("10000000"))>0)throw new IllegalArgumentException("Target search range is too large for requested precision");
        if(r.minMonthlyFreeCashflow()==null&&r.minEmergencyFundMonths()==null&&r.maxDebtServiceBurden()==null)throw new IllegalArgumentException("At least one explicit target constraint is required");
        if(r.minEmergencyFundMonths()!=null&&r.minEmergencyFundMonths().signum()<0||r.maxDebtServiceBurden()!=null&&(r.maxDebtServiceBurden().signum()<0||r.maxDebtServiceBurden().compareTo(BigDecimal.ONE)>0))throw new IllegalArgumentException("Invalid target constraints");
    }
    public record Request(UUID financialSnapshotId,BigDecimal annualInterestRate,int termMonths,BigDecimal cashContribution,RepaymentType repaymentType,BigDecimal minHousePrice,BigDecimal maxHousePrice,BigDecimal searchPrecision,BigDecimal minMonthlyFreeCashflow,BigDecimal minEmergencyFundMonths,BigDecimal maxDebtServiceBurden){}
    public record Result(boolean feasible,BigDecimal maximumTestedFeasibleHousePrice,BigDecimal requiredMortgage,BigDecimal monthlyPayment,BigDecimal postPurchaseMonthlyFreeCashflow,BigDecimal emergencyFundMonths,BigDecimal debtServiceBurden,String bindingConstraint,long iterations,BigDecimal searchPrecision,String ruleVersion,List<String>warnings){public Result{warnings=List.copyOf(warnings);}}
}
