package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanops.fincompass.simulation.ScenarioService;
import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentOrchestratorTest {
 @Test void unsupportedModelNumberFallsBackAndIsAudited(){
  AgentModelClient model=model("근거 없는 금리 9%를 보장합니다");AgentToolbox toolbox=mock(AgentToolbox.class);AgentStore store=mock(AgentStore.class);PersonalFinanceCipher cipher=mock(PersonalFinanceCipher.class);when(cipher.contentHash(any())).thenReturn("hash");
  when(toolbox.execute(anyString(),eq(ToolName.GET_FINANCIAL_SNAPSHOT),any(),any())).thenAnswer(a->new AgentToolbox.Result("snapshot",new ObjectMapper().valueToTree(Map.of("amount",100)),a.getArgument(3)));
  var orchestrator=new AgentOrchestrator(model,toolbox,store,mock(FinancialTwinStore.class),mock(ScenarioService.class),cipher,new ObjectMapper(),new NumericGrounding(),Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"),ZoneOffset.UTC));
  var response=orchestrator.chat("owner",new ChatRequest("상태 알려줘",null,null,null,null,null,null));
  assertThat(response.answer()).doesNotContain("9%", "보장");ArgumentCaptor<RunTrace> trace=ArgumentCaptor.forClass(RunTrace.class);verify(store).save(eq("owner"),trace.capture());assertThat(trace.getValue().groundingFallbackUsed()).isTrue();assertThat(trace.getValue().warnings()).contains("UNSUPPORTED_NUMBER_FALLBACK");
 }
 @Test void toolExceptionReturnsFailedResponseAndCompleteFailureTrace(){
  AgentToolbox toolbox=mock(AgentToolbox.class);when(toolbox.execute(anyString(),any(),any(),any())).thenThrow(new IllegalStateException("down"));AgentStore store=mock(AgentStore.class);PersonalFinanceCipher cipher=mock(PersonalFinanceCipher.class);when(cipher.contentHash(any())).thenReturn("hash");
  var orchestrator=new AgentOrchestrator(model("ignored"),toolbox,store,mock(FinancialTwinStore.class),mock(ScenarioService.class),cipher,new ObjectMapper(),new NumericGrounding(),Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"),ZoneOffset.UTC));
  var response=orchestrator.chat("owner",new ChatRequest("상태 알려줘",null,null,null,null,null,null));assertThat(response.status()).isEqualTo("FAILED");assertThat(response.warnings()).contains("TOOL_FAILURE:get_financial_snapshot");ArgumentCaptor<RunTrace> trace=ArgumentCaptor.forClass(RunTrace.class);verify(store).save(eq("owner"),trace.capture());assertThat(trace.getValue().toolCalls()).singleElement().satisfies(c->assertThat(c.status()).isEqualTo("FAILED"));
 }
 private AgentModelClient model(String draft){return new AgentModelClient(){public String provider(){return "test";}public String version(){return "v";}public Plan plan(ChatRequest r){return new Plan(Intent.UNKNOWN,List.of(ToolName.GET_FINANCIAL_SNAPSHOT));}public String draft(DraftContext c){return draft;}};}
}
