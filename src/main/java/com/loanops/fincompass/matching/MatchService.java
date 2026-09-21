package com.loanops.fincompass.matching;
import com.loanops.fincompass.simulation.*;
import com.loanops.fincompass.twin.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import static com.loanops.fincompass.matching.MatchRecords.*;
import static com.loanops.fincompass.matching.MatchingModels.*;
public final class MatchService {
 private final MatchStore store;private final ProductCatalogReader products;private final FinancialTwinStore twins;private final ScenarioService scenarios;private final SimulationEngine simulations;private final MatchingEngine engine;private final Clock clock;
 public MatchService(MatchStore store,ProductCatalogReader products,FinancialTwinStore twins,ScenarioService scenarios,SimulationEngine simulations,MatchingEngine engine,Clock clock){this.store=store;this.products=products;this.twins=twins;this.scenarios=scenarios;this.simulations=simulations;this.engine=engine;this.clock=clock;}
 public Run match(String owner,MatchRequest input){if(input==null)throw new IllegalArgumentException("Match request required");
  ScenarioRecords.Scenario scenario=null;UUID snapshotId=null;BigDecimal required=input.requiredLoanAmount();Integer years=input.preferredTermYears();
  if(input.scenarioId()!=null){scenario=scenarios.get(owner,input.scenarioId());if(!"HOME".equals(scenario.kind()))throw new IllegalArgumentException("Matching scenario must be HOME");snapshotId=scenario.financialSnapshotId();required=scenario.homeInput().mortgageAmount();years=scenario.homeInput().termMonths()/12;
   if(input.requiredLoanAmount()!=null&&input.requiredLoanAmount().compareTo(required)!=0||input.preferredTermYears()!=null&&!input.preferredTermYears().equals(years))throw new IllegalArgumentException("Explicit request conflicts with frozen scenario");}
  if(snapshotId==null){snapshotId=twins.latest(owner).financialSnapshotId();}
  var snapshot=twins.get(owner,snapshotId);BigDecimal max=input.maxMonthlyPayment();if(max==null){var metric=snapshot.result().metrics().get("MONTHLY_FREE_CASHFLOW");if(metric!=null&&metric.value()!=null&&metric.value().signum()>0)max=metric.value();}
  Request effective=new Request(input.scenarioId(),required,max,years,input.repaymentPreferences(),input.topK()==null?5:input.topK());
  var now=clock.instant();var all=engine.evaluate(effective,products.read(),snapshot,scenario,simulations,now);var selected=all.stream().filter(e->e.rank()==null||e.rank()<=effective.topK()).toList();
  List<String> warnings=new ArrayList<>();if(input.maxMonthlyPayment()==null)warnings.add("MAX_PAYMENT_DERIVED_FROM_CURRENT_FREE_CASHFLOW");
  return store.save(owner,new Run(UUID.randomUUID(),snapshotId,input.scenarioId(),now,snapshot.calculationRuleVersion(),SimulationEngine.RULE_VERSION,MatchingEngine.SCORE_RULE_VERSION,MatchingEngine.RATE_POLICY_VERSION,effective,effective,selected,warnings,List.of("REVIEW_CANDIDATES_NOT_APPROVAL","LEGACY_PRODUCT_COLLECTION_MODE_MAY_BE_UNSPECIFIED")));}
 public Run get(String owner,UUID id){return store.get(owner,id);}public List<Run> list(String owner){return store.list(owner);}
}
