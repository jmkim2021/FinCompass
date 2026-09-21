package com.loanops.fincompass.agent;

import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;

/** Offline/CI model: deterministic intent selection and faithful pass-through of the grounded draft. */
public final class DeterministicAgentModelClient implements AgentModelClient {
    @Override public String provider(){return "DEMO_REPLAY";}
    @Override public String version(){return "deterministic-agent-v1";}
    @Override public Plan plan(ChatRequest request){
        String q=request==null||request.question()==null?"":request.question().toLowerCase(Locale.ROOT);
        if(q.contains("얼마까지")||q.contains("조건을 맞추")||q.contains("simulation boundary")||q.contains("target scenario"))
            return new Plan(Intent.TARGET_SCENARIO_SEARCH,List.of(ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_CASHFLOW_ANALYSIS,
                    ToolName.GET_DEBT_ANALYSIS,ToolName.SEARCH_HOME_PURCHASE_TARGET,ToolName.GET_CALCULATION_TRACE));
        if(q.contains("아파트")||q.contains("주택")||q.contains("집")||q.contains("home")||q.contains("buy"))
            return new Plan(Intent.HOME_PURCHASE,List.of(ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_DEBT_ANALYSIS,
                    ToolName.GET_CASHFLOW_ANALYSIS,ToolName.ESTIMATE_LOAN_CAPACITY,ToolName.SEARCH_LOAN_PRODUCTS,
                    ToolName.SIMULATE_HOME_PURCHASE,ToolName.MATCH_LOAN_PRODUCTS,ToolName.COMPARE_PRODUCT_CANDIDATES,
                    ToolName.RUN_STRESS_TEST,ToolName.GET_OPPORTUNITY_EVENTS,ToolName.GET_CALCULATION_TRACE,
                    ToolName.GET_PRODUCT_MATCH_TRACE));
        if(q.contains("상품")||q.contains("대출")||q.contains("loan"))return new Plan(Intent.PRODUCT_REVIEW,List.of(
                ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.SEARCH_LOAN_PRODUCTS,ToolName.MATCH_LOAN_PRODUCTS,ToolName.GET_PRODUCT_MATCH_TRACE));
        if(q.contains("기회")||q.contains("위험")||q.contains("경고")||q.contains("opportun"))return new Plan(Intent.OPPORTUNITIES,List.of(
                ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_OPPORTUNITY_EVENTS));
        if(q.contains("재무")||q.contains("현금")||q.contains("부채")||q.contains("financial"))return new Plan(Intent.FINANCIAL_OVERVIEW,List.of(
                ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_CASHFLOW_ANALYSIS,ToolName.GET_DEBT_ANALYSIS,ToolName.GET_CALCULATION_TRACE));
        return new Plan(Intent.UNKNOWN,List.of(ToolName.GET_FINANCIAL_SNAPSHOT));
    }
    @Override public String draft(DraftContext context){return context.suggestedAnswer();}
}
