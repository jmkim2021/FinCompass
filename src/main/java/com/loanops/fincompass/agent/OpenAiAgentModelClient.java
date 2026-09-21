package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.loanops.fincompass.agent.AgentModels.*;

/** Small OpenAI Responses API adapter. Financial calculations remain in local deterministic tools. */
public final class OpenAiAgentModelClient implements AgentModelClient {
    private final RestClient http;private final ObjectMapper json;private final String model;
    public OpenAiAgentModelClient(String baseUrl,String apiKey,String model,ObjectMapper json){
        if(apiKey==null||apiKey.isBlank())throw new IllegalArgumentException("OPENAI_API_KEY is required when FINCOMPASS_AGENT_MODE=LLM");
        if(model==null||model.isBlank())throw new IllegalArgumentException("OPENAI_MODEL is required when FINCOMPASS_AGENT_MODE=LLM");
        this.http=RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()).baseUrl(baseUrl).defaultHeader("Authorization","Bearer "+apiKey).build();this.json=json;this.model=model;
    }
    @Override public String provider(){return "OPENAI_RESPONSES";}
    @Override public String version(){return model;}
    @Override public Plan plan(ChatRequest request){
        String allowed=String.join(",",AgentToolbox.CATALOG.stream().map(ToolName::externalName).sorted().toList());
        String instructions="""
            You plan a Korean financial decision-support workflow. Return JSON only with keys intent and tools.
            intent must be HOME_PURCHASE, TARGET_SCENARIO_SEARCH, FINANCIAL_OVERVIEW, PRODUCT_REVIEW, OPPORTUNITIES, or UNKNOWN.
            tools must contain only names from the supplied catalog and must never include SQL, code execution, or arithmetic tools.
            Select tools for orchestration; all financial values are calculated by the local backend.
            """;
        JsonNode response=call(instructions,json.valueToTree(Map.of("request",request,"toolCatalog",allowed)),400);
        try{
            JsonNode parsed=json.readTree(outputText(response));Intent intent=Intent.valueOf(parsed.path("intent").asText());List<ToolName>tools=new ArrayList<>();
            parsed.path("tools").forEach(v->tools.add(tool(v.asText())));if(tools.isEmpty())tools.add(ToolName.GET_FINANCIAL_SNAPSHOT);return new Plan(intent,tools);
        }catch(Exception error){throw new IllegalStateException("Invalid LLM planning response",error);}
    }
    @Override public String draft(DraftContext context){
        String instructions="""
            Explain the supplied deterministic result in Korean. Never add, calculate, transform, or infer a financial number.
            Preserve warnings and limitations. Never claim approval, eligibility, a best product, a safe price, or professional advice.
            If a number is not already present in suggestedAnswer, do not use it.
            """;
        return outputText(call(instructions,json.valueToTree(context),800));
    }
    private JsonNode call(String instructions,JsonNode input,int maxTokens){
        JsonNode response=http.post().uri("/v1/responses").body(Map.of("model",model,"store",false,"instructions",instructions,"input",input.toString(),"max_output_tokens",maxTokens)).retrieve().body(JsonNode.class);
        if(response==null)throw new IllegalStateException("Empty LLM response");return response;
    }
    private String outputText(JsonNode response){
        for(JsonNode item:response.path("output"))for(JsonNode content:item.path("content"))if("output_text".equals(content.path("type").asText())&&!content.path("text").asText().isBlank())return content.path("text").asText();
        throw new IllegalStateException("LLM response has no output_text");
    }
    private ToolName tool(String external){return AgentToolbox.CATALOG.stream().filter(v->v.externalName().equals(external)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown tool: "+external));}
}
