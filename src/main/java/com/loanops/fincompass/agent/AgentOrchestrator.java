package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.NullNode;
import com.loanops.fincompass.matching.MatchingModels;
import com.loanops.fincompass.simulation.ScenarioService;
import com.loanops.fincompass.twin.FinancialTwinStore;
import com.loanops.personal.canonical.PersonalFinanceCipher;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;

/** Orchestrates only registered tools and validates every numeric token before persistence. */
public final class AgentOrchestrator {
    private final AgentModelClient model;private final AgentToolbox tools;private final AgentStore store;private final FinancialTwinStore twins;
    private final ScenarioService scenarios;private final PersonalFinanceCipher cipher;private final ObjectMapper json;private final NumericGrounding grounding;private final Clock clock;
    public AgentOrchestrator(AgentModelClient model,AgentToolbox tools,AgentStore store,FinancialTwinStore twins,ScenarioService scenarios,
                             PersonalFinanceCipher cipher,ObjectMapper json,NumericGrounding grounding,Clock clock){this.model=model;this.tools=tools;this.store=store;this.twins=twins;this.scenarios=scenarios;this.cipher=cipher;this.json=json;this.grounding=grounding;this.clock=clock;}
    public ChatResponse chat(String owner,ChatRequest request){
        if(request==null||request.question()==null||request.question().isBlank())throw new IllegalArgumentException("Question required");
        UUID runId=UUID.randomUUID();Instant started=clock.instant();Plan plan=model.plan(request);List<String>missing=missing(plan.intent(),request);
        AgentToolbox.Context context=new AgentToolbox.Context();List<ToolCall>calls=new ArrayList<>();List<JsonNode>results=new ArrayList<>();List<String>warnings=new ArrayList<>();List<String>limitations=new ArrayList<>();boolean failed=false;
        int ordinal=0;
        for(ToolName tool:plan.tools()){
            if(!missing.isEmpty()&&requiresScenario(tool))break;
            Instant toolStarted=clock.instant();JsonNode params=json.valueToTree(Map.of("request",request,"snapshotId",String.valueOf(context.snapshotId),"scenarioId",String.valueOf(context.scenarioId),"matchRunId",String.valueOf(context.matchRunId)));
            try{
                AgentToolbox.Result outcome=tools.execute(owner,tool,request,context);JsonNode result=outcome.value();results.add(result);collect(result,"warnings",warnings);collect(result,"limitations",limitations);
                calls.add(new ToolCall(UUID.randomUUID(),ordinal++,tool.externalName(),toolStarted,clock.instant(),"SUCCESS",params,result,cipher.contentHash(params),cipher.contentHash(result),null));
            }catch(Exception error){
                warnings.add("TOOL_FAILURE:"+tool.externalName());limitations.add("일부 분석 도구가 완료되지 않았습니다.");
                calls.add(new ToolCall(UUID.randomUUID(),ordinal++,tool.externalName(),toolStarted,clock.instant(),"FAILED",params,NullNode.instance,cipher.contentHash(params),null,error.getClass().getSimpleName()));failed=true;break;
            }
        }
        String status=failed?"FAILED":missing.isEmpty()?"COMPLETED":"NEEDS_INPUT";
        String deterministic=render(owner,plan.intent(),request,missing,context,status);
        String draft;
        try{draft=model.draft(new DraftContext(deterministic,plan.intent(),warnings,limitations));}catch(Exception ignored){draft=deterministic;warnings.add("MODEL_DRAFT_FAILURE_FALLBACK");}
        Set<String>evidence=grounding.evidence(request.question(),json.valueToTree(request),results);boolean fallback=!grounding.supported(draft,evidence);String answer=fallback?deterministic:draft;
        if(fallback)warnings.add("UNSUPPORTED_NUMBER_FALLBACK");
        if(!grounding.supported(answer,evidence))throw new IllegalStateException("Deterministic explanation failed numeric grounding");
        RunTrace trace=new RunTrace(runId,started,clock.instant(),status,model.provider(),model.version(),request.question(),plan.intent(),context.snapshotId,context.scenarioId,context.matchRunId,context.ruleVersions,context.assumptions,warnings.stream().distinct().toList(),limitations.stream().distinct().toList(),evidence,calls,answer,fallback);
        store.save(owner,trace);
        return new ChatResponse(runId,status,answer,plan.intent(),context.snapshotId,context.scenarioId,context.matchRunId,missing,context.assumptions,trace.warnings(),trace.limitations());
    }
    public RunTrace trace(String owner,UUID id){return store.get(owner,id);}
    private String render(String owner,Intent intent,ChatRequest r,List<String>missing,AgentToolbox.Context c,String status){
        if("FAILED".equals(status))return "분석 도구 실행이 완료되지 않았습니다. 저장된 경고와 실행 추적을 확인해 주세요.";
        if(!missing.isEmpty())return "시뮬레이션을 위해 "+String.join(", ",missing)+" 정보가 필요합니다. 값은 가정하지 않고 입력을 기다립니다.";
        if(intent==Intent.HOME_PURCHASE&&c.scenarioId!=null){
            var snapshot=twins.get(owner,c.snapshotId);var scenario=scenarios.get(owner,c.scenarioId);var metrics=snapshot.result().metrics();var home=scenario.homeResult();
            String asset=value(metrics.get("TOTAL_ASSETS"));String debt=value(metrics.get("TOTAL_LIABILITIES"));String free=value(metrics.get("MONTHLY_FREE_CASHFLOW"));
            return "현재 스냅샷의 총자산은 "+asset+"원, 부채는 "+debt+"원, 월 여유자금은 "+free+"원입니다. 입력한 주택가격 "+plain(r.housePrice())+"원, 현금기여 "+plain(r.cashContribution())+"원, 주담대 "+plain(r.mortgageAmount())+"원, 연이율 "+plain(r.annualInterestRate().multiply(new BigDecimal("100")))+"%, "+(r.termYears()*12)+"개월 가정에서 월 상환액은 "+plain(home.mortgage().regularMonthlyPayment())+"원, 구매 후 월 여유자금은 "+value(home.after().get("MONTHLY_FREE_CASHFLOW"))+"원입니다. 이는 재무 시뮬레이션이며 실제 승인이나 적격성을 보장하지 않습니다.";
        }
        if(intent==Intent.UNKNOWN)return "요청 의도를 확인하기 어렵습니다. 재무 현황, 주택 구매 시뮬레이션, 대출상품 검토 또는 기회·위험 점검 중 원하는 분석을 알려 주세요.";
        return "검증된 재무 스냅샷과 결정론적 도구 결과를 확인했습니다. 상세 수치와 근거는 실행 추적에서 확인할 수 있습니다.";
    }
    private List<String>missing(Intent intent,ChatRequest r){if(intent!=Intent.HOME_PURCHASE)return List.of();List<String>m=new ArrayList<>();if(r.housePrice()==null)m.add("주택가격");if(r.cashContribution()==null)m.add("현금기여액");if(r.mortgageAmount()==null)m.add("주담대금액");if(r.annualInterestRate()==null)m.add("연이율");if(r.termYears()==null)m.add("상환기간");return m;}
    private boolean requiresScenario(ToolName tool){return EnumSet.of(ToolName.SIMULATE_HOME_PURCHASE,ToolName.RUN_STRESS_TEST,ToolName.COMPARE_SCENARIOS,ToolName.MATCH_LOAN_PRODUCTS,ToolName.SIMULATE_PRODUCT_CANDIDATE,ToolName.COMPARE_PRODUCT_CANDIDATES,ToolName.GET_PRODUCT_MATCH_TRACE).contains(tool);}
    private void collect(JsonNode node,String field,List<String> values){if(node==null)return;if(node.isObject()){var found=node.get(field);if(found!=null&&found.isArray())found.forEach(v->values.add(v.asText()));node.elements().forEachRemaining(v->collect(v,field,values));}else if(node.isArray())node.forEach(v->collect(v,field,values));}
    private String value(com.loanops.fincompass.twin.TwinModels.Metric metric){return metric==null||metric.value()==null?"확인 불가":plain(metric.value());}
    private String plain(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
