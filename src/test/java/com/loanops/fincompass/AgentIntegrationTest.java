package com.loanops.fincompass;

import com.loanops.fincompass.agent.*;
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
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;
import static com.loanops.fincompass.api.TwinController.DEMO_OWNER;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("fincompass-demo")
class AgentIntegrationTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("fincompass.open-banking.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));}
 @Autowired AgentOrchestrator agent;@Autowired AgentToolbox tools;@Autowired JdbcTemplate jdbc;@Autowired MockMvc http;
 private ChatRequest golden(){return new ChatRequest("7억 아파트를 사도 될까?",new BigDecimal("700000000"),new BigDecimal("110000000"),new BigDecimal("590000000"),new BigDecimal("0.045"),30,null);}

 @Test void goldenQueryUsesToolsAndReturnsGroundedDeterministicAnswer(){var response=agent.chat(DEMO_OWNER,golden());assertThat(response.status()).isEqualTo("COMPLETED");assertThat(response.answer()).contains("2989443.33","-1789443.33","실제 승인이나 적격성을 보장하지 않습니다");assertThat(response.scenarioId()).isNotNull();assertThat(response.productMatchRunId()).isNotNull();var trace=agent.trace(DEMO_OWNER,response.agentRunId());assertThat(trace.toolCalls()).extracting(ToolCall::toolName).contains("get_financial_snapshot","simulate_home_purchase","match_loan_products","run_stress_test","get_calculation_trace","get_product_match_trace");assertThat(trace.toolCalls()).allSatisfy(c->{assertThat(c.paramsHash()).isNotBlank();assertThat(c.completedAt()).isNotNull();});assertThat(trace.ruleVersions()).containsKeys("calculation","simulation","productMatch","ratePolicy");assertThat(trace.allowedNumericEvidence()).isNotEmpty();assertThat(trace.groundingFallbackUsed()).isFalse();}
 @Test void missingCriticalInputsAreRequestedWithoutInventingAssumptions(){var response=agent.chat(DEMO_OWNER,new ChatRequest("7억 아파트를 사도 될까?",new BigDecimal("700000000"),null,null,null,null,null));assertThat(response.status()).isEqualTo("NEEDS_INPUT");assertThat(response.missingInputs()).containsExactly("현금기여액","주담대금액","연이율","상환기간");assertThat(response.scenarioId()).isNull();assertThat(response.answer()).contains("값은 가정하지 않고 입력을 기다립니다");}
 @Test void repeatProducesSameExplanation(){assertThat(agent.chat(DEMO_OWNER,golden()).answer()).isEqualTo(agent.chat(DEMO_OWNER,golden()).answer());}
 @Test void noServingProductsRemainExplicitRatherThanInvented(){var response=agent.chat(DEMO_OWNER,golden());var trace=agent.trace(DEMO_OWNER,response.agentRunId());var match=trace.toolCalls().stream().filter(c->c.toolName().equals("match_loan_products")).findFirst().orElseThrow();assertThat(match.result().path("candidates")).isEmpty();assertThat(response.answer()).doesNotContain("승인됩니다","최적","보장 금리");}
 @Test void tracePayloadsAreEncryptedAndImmutable(){var response=agent.chat(DEMO_OWNER,golden());String encrypted=jdbc.queryForObject("SELECT payload_ciphertext FROM loanops.agent_run WHERE id=?",String.class,response.agentRunId());assertThat(encrypted).doesNotContain("7억","700000000","2989443.33");assertThatThrownBy(()->jdbc.update("DELETE FROM loanops.agent_run WHERE id=?",response.agentRunId())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
 @Test void toolFailureIsTypedAndDoesNotExecuteCodeOrSql(){var context=new AgentToolbox.Context();assertThatThrownBy(()->tools.execute(DEMO_OWNER,ToolName.RUN_STRESS_TEST,new ChatRequest("stress",null,null,null,null,null,null),context)).isInstanceOf(IllegalArgumentException.class).hasMessage("Scenario required");assertThat(AgentToolbox.CATALOG).noneMatch(v->v.externalName().contains("sql")||v.externalName().contains("code"));}
 @Test void chatAndTraceApisExposeAuditableRun()throws Exception{String body="{\"question\":\"7억 아파트를 사도 될까?\",\"housePrice\":700000000,\"cashContribution\":110000000,\"mortgageAmount\":590000000,\"annualInterestRate\":0.045,\"termYears\":30}";String json=http.perform(post("/api/v1/agent/chat").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("2989443.33"))).andReturn().getResponse().getContentAsString();String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("agentRunId").asText();http.perform(get("/api/v1/agent/runs/{id}/trace",id)).andExpect(status().isOk()).andExpect(jsonPath("$.toolCalls").isArray()).andExpect(jsonPath("$.finalExplanation").exists());}
}
