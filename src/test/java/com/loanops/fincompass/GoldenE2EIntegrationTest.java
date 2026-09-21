package com.loanops.fincompass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanops.fincompass.agent.AgentOrchestrator;
import com.loanops.fincompass.matching.*;
import com.loanops.fincompass.opportunity.OpportunityService;
import com.loanops.fincompass.simulation.*;
import com.loanops.fincompass.twin.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;
import static com.loanops.fincompass.api.TwinController.DEMO_OWNER;
import static com.loanops.fincompass.matching.MatchingModels.Status;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"fincompass-demo","golden-products"})
class GoldenE2EIntegrationTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("fincompass.open-banking.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));}
 @Autowired JdbcTemplate jdbc;@Autowired FinancialTwinStore twins;@Autowired ScenarioService scenarios;@Autowired ProductCatalogReader products;@Autowired MatchService matches;@Autowired OpportunityService opportunities;@Autowired AgentOrchestrator agent;@Autowired ObjectMapper json;@Autowired MockMvc http;

 @Test void cleanDatabaseRunsCompleteCredentialFreeGoldenJourneyAndEmitsMeasuredResults()throws Exception{
  assertThat(jdbc.queryForObject("SELECT max(version::int) FROM loanops.flyway_schema_history",Integer.class)).isEqualTo(15);
  var snapshot=twins.latest(DEMO_OWNER);assertMetric(snapshot,"TOTAL_ASSETS","120000000");assertMetric(snapshot,"TOTAL_LIABILITIES","30000000");assertMetric(snapshot,"NET_WORTH","90000000");assertMetric(snapshot,"MONTHLY_FREE_CASHFLOW","1200000");
  assertThat(snapshot.result().verifiedAnnualIncome()).isEqualByComparingTo("66000000");assertThat(snapshot.input().sourceRefs()).extracting(TwinModels.SourceRef::sourceType).contains("OPEN_BANKING_TEST","PUBLIC_MYDATA_MOCK");
  var catalog=products.read();assertThat(catalog).hasSize(4).allSatisfy(p->{assertThat(p.sourceType()).isEqualTo("REAL_PRODUCT_DATA");assertThat(p.sourceMode()).isEqualTo("DEMO_REPLAY");assertThat(p.productRawHash()).hasSize(64);});
  var alternatives=ScenarioExamples.alternatives(snapshot.financialSnapshotId()).stream().map(input->scenarios.createHome(DEMO_OWNER,input)).toList();var comparison=scenarios.compare(DEMO_OWNER,alternatives.stream().map(ScenarioRecords.Scenario::scenarioId).toList());assertThat(comparison.comparisonResults()).hasSize(4);
  ChatRequest request=new ChatRequest("7억 아파트를 사도 될까?",d("700000000"),d("110000000"),d("590000000"),d("0.045"),30,null);var response=agent.chat(DEMO_OWNER,request);assertThat(response.status()).isEqualTo("COMPLETED");assertThat(response.answer()).contains("2989443.33","-1789443.33");
  var home=scenarios.get(DEMO_OWNER,response.scenarioId());assertThat(home.homeResult().mortgage().regularMonthlyPayment()).isEqualByComparingTo("2989443.33");assertThat(home.homeResult().mortgage().totalInterest()).isEqualByComparingTo("486199597.17");assertThat(home.homeResult().after().get("MONTHLY_FREE_CASHFLOW").value()).isEqualByComparingTo("-1789443.33");
  var stress=scenarios.list(DEMO_OWNER).stream().filter(s->"STRESS".equals(s.kind())).findFirst().orElseThrow();assertThat(stress.stressResults()).hasSize(11);var plusTwo=stress.stressResults().stream().filter(s->"RATE_PLUS_2PP".equals(s.shock().label())).findFirst().orElseThrow();assertThat(plusTwo.result().mortgage().regularMonthlyPayment()).isEqualByComparingTo("3729201.34");assertThat(plusTwo.result().after().get("MONTHLY_FREE_CASHFLOW").value()).isEqualByComparingTo("-2529201.34");
  var match=matches.get(DEMO_OWNER,response.productMatchRunId());assertThat(match.candidates()).hasSize(4).allSatisfy(c->{assertThat(c.status()).isEqualTo(Status.INSUFFICIENT_DATA);assertThat(c.score()).isNull();assertThat(c.rank()).isNull();});
  var events=opportunities.active(DEMO_OWNER);assertThat(events).extracting(com.loanops.fincompass.opportunity.OpportunityModels.Event::type).contains(com.loanops.fincompass.opportunity.OpportunityModels.Type.LIQUIDITY_RISK,com.loanops.fincompass.opportunity.OpportunityModels.Type.DEBT_BURDEN_WARNING);
  var trace=agent.trace(DEMO_OWNER,response.agentRunId());assertThat(trace.toolCalls()).hasSize(12).allSatisfy(call->{assertThat(call.paramsHash()).isNotBlank();assertThat(call.status()).isEqualTo("SUCCESS");});assertThat(trace.groundingFallbackUsed()).isFalse();
  http.perform(get("/api/v1/financial-snapshots/latest")).andExpect(status().isOk());http.perform(get("/api/v1/scenarios")).andExpect(status().isOk());http.perform(get("/api/v1/loan-product-matches/{id}",response.productMatchRunId())).andExpect(status().isOk());http.perform(get("/api/v1/opportunities")).andExpect(status().isOk());http.perform(get("/api/v1/agent/runs/{id}/trace",response.agentRunId())).andExpect(status().isOk());
  writeReport(snapshot,alternatives,comparison,home,stress,match,events,trace,response);
 }
 private void writeReport(TwinModels.Snapshot snapshot,List<ScenarioRecords.Scenario> alternatives,ScenarioRecords.Scenario comparison,ScenarioRecords.Scenario home,ScenarioRecords.Scenario stress,MatchRecords.Run match,List<com.loanops.fincompass.opportunity.OpportunityModels.Event> events,RunTrace trace,ChatResponse response)throws Exception{
  Map<String,Object> report=new LinkedHashMap<>();report.put("personaInputs",Map.of("verifiedAnnualIncome",snapshot.result().verifiedAnnualIncome(),"liquidAssets",snapshot.result().metrics().get("LIQUID_ASSETS").value(),"existingDebt",snapshot.result().metrics().get("TOTAL_LIABILITIES").value(),"observedMonthlyIncome",snapshot.result().metrics().get("OBSERVED_MONTHLY_INFLOW").value(),"monthlyLivingExpense",snapshot.result().monthlyLivingExpense(),"monthlyDebtService",snapshot.result().monthlyDebtService(),"monthlyFixedObligations",snapshot.result().monthlyFixedObligations()));report.put("financialTwin",values(snapshot.result().metrics()));report.put("home700m",Map.of("input",home.homeInput(),"mortgage",home.homeResult().mortgage(),"after",values(home.homeResult().after())));report.put("stress",stress.stressResults().stream().map(v->Map.of("label",v.shock().label(),"monthlyPayment",v.result().mortgage().regularMonthlyPayment(),"monthlyFreeCashflow",v.result().after().get("MONTHLY_FREE_CASHFLOW").value(),"debtBurden",v.result().after().get("DEBT_SERVICE_BURDEN").value(),"emergencyFundMonths",v.result().after().get("EMERGENCY_FUND_MONTHS").value())).toList());report.put("alternatives",alternatives.stream().map(v->Map.of("input",v.homeInput(),"monthlyPayment",v.homeResult().mortgage().regularMonthlyPayment(),"totalInterest",v.homeResult().mortgage().totalInterest(),"after",values(v.homeResult().after()))).toList());report.put("comparisonScenarioId",comparison.scenarioId());report.put("productCandidates",match.candidates().stream().map(v->Map.of("product",v.product(),"status",v.status(),"score",String.valueOf(v.score()),"rank",String.valueOf(v.rank()),"warnings",v.warnings(),"limitations",v.limitations())).toList());report.put("opportunities",events);report.put("agent",Map.of("status",response.status(),"answer",response.answer(),"toolCalls",trace.toolCalls().size(),"groundingFallbackUsed",trace.groundingFallbackUsed(),"ruleVersions",trace.ruleVersions()));Path path=Path.of("build/reports/fincompass/golden-results.json");Files.createDirectories(path.getParent());json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
 }
 private Map<String,Object>values(Map<String,TwinModels.Metric> metrics){Map<String,Object> values=new LinkedHashMap<>();metrics.forEach((k,v)->values.put(k,v.value()));return values;}
 private void assertMetric(TwinModels.Snapshot snapshot,String name,String value){assertThat(snapshot.result().metrics().get(name).value()).isEqualByComparingTo(value);}
 private BigDecimal d(String value){return new BigDecimal(value);}
}
