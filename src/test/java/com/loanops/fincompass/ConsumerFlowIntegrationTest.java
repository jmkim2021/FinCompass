package com.loanops.fincompass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.Base64;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("fincompass-demo")
class ConsumerFlowIntegrationTest {
 @Container static final PostgreSQLContainer DB=new PostgreSQLContainer(DockerImageName.parse("postgres:17.11-alpine"));
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);r.add("fincompass.open-banking.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));}
 @Autowired MockMvc http;@Autowired ObjectMapper json;
 @Test void goldenConsumerJourneyHasAllApiViews()throws Exception{
  http.perform(get("/api/v1/financial-snapshots/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.result.metrics.NET_WORTH.value").value(90000000));
  String body="{\"question\":\"7억 아파트를 사도 될까?\",\"housePrice\":700000000,\"cashContribution\":110000000,\"mortgageAmount\":590000000,\"annualInterestRate\":0.045,\"termYears\":30}";
  String response=http.perform(post("/api/v1/agent/chat").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andReturn().getResponse().getContentAsString();String run=json.readTree(response).path("agentRunId").asText();
  http.perform(get("/api/v1/scenarios")).andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").exists());http.perform(get("/api/v1/loan-product-matches")).andExpect(status().isOk()).andExpect(jsonPath("$[0].scoreRuleVersion").exists());http.perform(get("/api/v1/opportunities")).andExpect(status().isOk()).andExpect(jsonPath("$[0].evidence").exists());http.perform(get("/api/v1/agent/runs/{id}/trace",run)).andExpect(status().isOk()).andExpect(jsonPath("$.toolCalls").isArray());
 }
}
