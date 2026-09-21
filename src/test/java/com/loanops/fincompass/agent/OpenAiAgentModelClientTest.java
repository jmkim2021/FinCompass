package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.loanops.fincompass.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiAgentModelClientTest {
    private WireMockServer wire;private OpenAiAgentModelClient client;
    @BeforeEach void start(){wire=new WireMockServer(0);wire.start();client=new OpenAiAgentModelClient(wire.baseUrl(),"test-only-key","test-model",new ObjectMapper());}
    @AfterEach void stop(){wire.stop();}
    @Test void plansWithResponsesApiAndClosedToolCatalog(){stub("{\"intent\":\"FINANCIAL_OVERVIEW\",\"tools\":[\"get_financial_snapshot\",\"get_cashflow_analysis\"]}");var plan=client.plan(new ChatRequest("현금흐름",null,null,null,null,null,null));assertThat(plan.intent()).isEqualTo(Intent.FINANCIAL_OVERVIEW);assertThat(plan.tools()).containsExactly(ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_CASHFLOW_ANALYSIS);wire.verify(postRequestedFor(urlEqualTo("/v1/responses")).withHeader("Authorization",equalTo("Bearer test-only-key")).withRequestBody(containing("\"store\":false")));}
    @Test void draftsOnlyProviderTextForSharedNumericGroundingGate(){stub("도구 결과를 확인했습니다.");assertThat(client.draft(new DraftContext("도구 결과를 확인했습니다.",Intent.FINANCIAL_OVERVIEW,List.of(),List.of()))).isEqualTo("도구 결과를 확인했습니다.");}
    @Test void rejectsMissingCredentialOnlyWhenLlmAdapterIsSelected(){assertThatThrownBy(()->new OpenAiAgentModelClient(wire.baseUrl(),"","test-model",new ObjectMapper())).hasMessageContaining("OPENAI_API_KEY");}
    private void stub(String text){wire.stubFor(post(urlEqualTo("/v1/responses")).willReturn(okJson("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":"+quote(text)+"}]}]}")));}
    private String quote(String text){try{return new ObjectMapper().writeValueAsString(text);}catch(Exception e){throw new RuntimeException(e);}}
}
