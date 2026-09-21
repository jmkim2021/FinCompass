package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanops.fincompass.matching.*;
import com.loanops.fincompass.opportunity.OpportunityService;
import com.loanops.fincompass.simulation.*;
import com.loanops.fincompass.twin.*;
import java.math.BigDecimal;
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;
import static com.loanops.fincompass.matching.MatchRecords.*;
import static com.loanops.fincompass.simulation.SimulationModels.*;

/** Closed tool registry. Every tool delegates to an application service; there is no SQL/code tool. */
public final class AgentToolbox {
    public static final Set<ToolName> CATALOG=Collections.unmodifiableSet(EnumSet.allOf(ToolName.class));
    private final ObjectMapper json;private final FinancialTwinStore twins;private final ScenarioService scenarios;private final TargetScenarioSearch targetSearch;private final MatchService matches;
    private final ProductCatalogReader products;private final OpportunityService opportunities;
    public AgentToolbox(ObjectMapper json,FinancialTwinStore twins,ScenarioService scenarios,TargetScenarioSearch targetSearch,MatchService matches,ProductCatalogReader products,OpportunityService opportunities){this.json=json;this.twins=twins;this.scenarios=scenarios;this.targetSearch=targetSearch;this.matches=matches;this.products=products;this.opportunities=opportunities;}
    public Result execute(String owner,ToolName tool,ChatRequest request,Context context){return switch(tool){
        case GET_FINANCIAL_SNAPSHOT -> snapshot(owner,context);
        case GET_CASHFLOW_ANALYSIS -> metricView(owner,context,"cashflow",List.of("OBSERVED_MONTHLY_INFLOW","OBSERVED_MONTHLY_OUTFLOW","MONTHLY_FREE_CASHFLOW"));
        case GET_DEBT_ANALYSIS -> metricView(owner,context,"debt",List.of("TOTAL_LIABILITIES","MONTHLY_DEBT_SERVICE","DEBT_SERVICE_BURDEN","DEBT_TO_ASSET"));
        case ESTIMATE_LOAN_CAPACITY -> envelope(owner,context);
        case SEARCH_LOAN_PRODUCTS -> result("products",products.read(),context);
        case SIMULATE_HOME_PURCHASE -> simulateHome(owner,request,context);
        case RUN_STRESS_TEST -> result("stress",scenarios.stress(owner,required(context.scenarioId,"Scenario required"),null),context);
        case COMPARE_SCENARIOS -> compare(owner,context);
        case MATCH_LOAN_PRODUCTS -> match(owner,request,context);
        case SIMULATE_PRODUCT_CANDIDATE -> candidate(owner,context,true);
        case COMPARE_PRODUCT_CANDIDATES -> candidate(owner,context,false);
        case GET_OPPORTUNITY_EVENTS -> result("opportunities",opportunities.detect(owner,context.scenarioId,context.matchRunId),context);
        case GET_CALCULATION_TRACE -> calculationTrace(owner,context);
        case GET_PRODUCT_MATCH_TRACE -> result("productMatchTrace",matches.get(owner,required(context.matchRunId,"Match run required")),context);
        case SEARCH_HOME_PURCHASE_TARGET -> target(owner,request,context);
    };}
    private Result snapshot(String owner,Context c){var value=twins.latest(owner);c.snapshotId=value.financialSnapshotId();c.ruleVersions.put("calculation",value.calculationRuleVersion());return result("financialSnapshot",value,c);}
    private Result metricView(String owner,Context c,String name,List<String> keys){var value=twins.get(owner,snapshot(owner,c).context.snapshotId);Map<String,Object> view=new LinkedHashMap<>();keys.forEach(k->view.put(k,value.result().metrics().get(k)));view.put("warnings",value.result().warnings());return result(name,view,c);}
    private Result envelope(String owner,Context c){var value=twins.get(owner,ensureSnapshot(owner,c));var free=value.result().metrics().get("MONTHLY_FREE_CASHFLOW");return result("financingEnvelope",Map.of("maximumAdditionalMonthlyPayment",free==null||free.value()==null?BigDecimal.ZERO:free.value().max(BigDecimal.ZERO),"principalCapacity", "INSUFFICIENT_DATA_WITHOUT_PRODUCT_RATE_AND_TERM","notApproval",true),c);}
    private Result simulateHome(String owner,ChatRequest r,Context c){HomeInput input=new HomeInput(ensureSnapshot(owner,c),r.housePrice(),r.cashContribution(),r.mortgageAmount(),r.annualInterestRate(),r.termYears()*12,RepaymentType.AMORTIZING,BigDecimal.ZERO,Adjustment.zero());var value=scenarios.createHome(owner,input);c.scenarioId=value.scenarioId();c.ruleVersions.put("simulation",value.simulationRuleVersion());c.assumptions.add("원리금균등상환");c.assumptions.add("별도 취득세·수수료 제외");return result("homeScenario",value,c);}
    private Result compare(String owner,Context c){if(c.comparisonScenarioIds.size()<2)throw new IllegalArgumentException("Two scenarios required");return result("scenarioComparison",scenarios.compare(owner,c.comparisonScenarioIds),c);}
    private Result match(String owner,ChatRequest r,Context c){var value=matches.match(owner,new MatchRequest(c.scenarioId,r.mortgageAmount(),r.maxMonthlyPayment(),r.termYears(),List.of("AMORTIZING"),5));c.matchRunId=value.matchRunId();c.ruleVersions.put("productMatch",value.scoreRuleVersion());c.ruleVersions.put("ratePolicy",value.ratePolicyVersion());return result("productMatches",value,c);}
    private Result candidate(String owner,Context c,boolean first){var run=matches.get(owner,required(c.matchRunId,"Match run required"));Object value=first?run.candidates().stream().map(v->(Object)v).findFirst().orElse(Map.of("status","NO_PRODUCTS")):Map.of("candidates",run.candidates(),"rankingPreserved",true);return result(first?"candidateSimulation":"candidateComparison",value,c);}
    private Result calculationTrace(String owner,Context c){var s=twins.get(owner,ensureSnapshot(owner,c));return result("calculationTrace",Map.of("financialSnapshotId",s.financialSnapshotId(),"inputFingerprint",s.inputFingerprint(),"calculationRuleVersion",s.calculationRuleVersion(),"sourceRefs",s.input().sourceRefs(),"supplementalFacts",s.input().facts(),"metrics",s.result().metrics(),"metricDefinitions",Map.of("OBSERVED_MONTHLY_OUTFLOW","classified non-transfer debit transactions / covered months","MODELED_MONTHLY_OUTFLOW","monthly living expense + scheduled debt service + modeled fixed obligations","MONTHLY_FREE_CASHFLOW","observed monthly inflow - living expense - scheduled debt service - modeled fixed obligations","EMERGENCY_FUND_MONTHS","liquid assets / explicit monthly essential expense","DEBT_SERVICE_BURDEN","annual scheduled debt service / verified annual income; analytical, not official DSR")),c);}
    private Result target(String owner,ChatRequest r,Context c){var value=targetSearch.search(owner,new TargetScenarioSearch.Request(ensureSnapshot(owner,c),r.annualInterestRate(),r.termYears()*12,r.cashContribution(),RepaymentType.AMORTIZING,r.searchMinHousePrice(),r.searchMaxHousePrice(),r.searchPrecision(),r.targetMonthlyFreeCashflow(),r.minEmergencyFundMonths(),r.maxDebtServiceBurden()));c.targetResult=value;c.ruleVersions.put("targetSearch",value.ruleVersion());c.assumptions.add("목표 시나리오 검색 범위와 제약은 사용자 입력");return result("targetScenarioSearch",value,c);}
    private UUID ensureSnapshot(String owner,Context c){if(c.snapshotId==null)c.snapshotId=twins.latest(owner).financialSnapshotId();return c.snapshotId;}
    private Result result(String name,Object value,Context c){return new Result(name,json.valueToTree(value),c);}
    private static <T>T required(T value,String message){if(value==null)throw new IllegalArgumentException(message);return value;}
    public static final class Context {UUID snapshotId;UUID scenarioId;UUID matchRunId;TargetScenarioSearch.Result targetResult;final List<UUID>comparisonScenarioIds=new ArrayList<>();final Map<String,String>ruleVersions=new LinkedHashMap<>();final List<String>assumptions=new ArrayList<>();}
    public record Result(String name,JsonNode value,Context context){}
}
