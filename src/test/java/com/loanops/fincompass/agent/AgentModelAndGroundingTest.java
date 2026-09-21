package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.loanops.fincompass.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.*;

class AgentModelAndGroundingTest {
    private final DeterministicAgentModelClient model=new DeterministicAgentModelClient();
    private final NumericGrounding grounding=new NumericGrounding();
    private final ObjectMapper json=new ObjectMapper();

    @Test void catalogContainsExactlyFourteenClosedTools(){assertThat(AgentToolbox.CATALOG).hasSize(14).contains(ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_PRODUCT_MATCH_TRACE);}
    @Test void selectsGeneralizedHomeFlow(){var plan=model.plan(new ChatRequest("7억 아파트를 사도 될까?",null,null,null,null,null,null));assertThat(plan.intent()).isEqualTo(Intent.HOME_PURCHASE);assertThat(plan.tools()).containsSubsequence(ToolName.GET_FINANCIAL_SNAPSHOT,ToolName.GET_DEBT_ANALYSIS,ToolName.GET_CASHFLOW_ANALYSIS).contains(ToolName.SIMULATE_HOME_PURCHASE,ToolName.MATCH_LOAN_PRODUCTS,ToolName.RUN_STRESS_TEST);}
    @Test void selectsOverviewProductsAndOpportunityIntents(){assertThat(model.plan(request("내 재무와 현금흐름")).intent()).isEqualTo(Intent.FINANCIAL_OVERVIEW);assertThat(model.plan(request("대출 상품 보여줘")).intent()).isEqualTo(Intent.PRODUCT_REVIEW);assertThat(model.plan(request("위험 경고 확인")).intent()).isEqualTo(Intent.OPPORTUNITIES);}
    @Test void supportedNumbersAcceptUserAndToolEvidence(){var req=new ChatRequest("7억",new BigDecimal("700000000"),null,null,new BigDecimal("0.045"),30,null);var evidence=grounding.evidence(req.question(),json.valueToTree(req),List.of(json.valueToTree(Map.of("payment",new BigDecimal("2989443.33")))));assertThat(grounding.supported("가격 700000000원, 금리 4.5%, 상환액 2989443.33원",evidence)).isTrue();}
    @Test void unsupportedFinancialNumberIsRejected(){var evidence=grounding.evidence("7억",json.valueToTree(Map.of("price",700000000)),List.of());assertThat(grounding.supported("보장 금리는 9%입니다",evidence)).isFalse();}
    private ChatRequest request(String value){return new ChatRequest(value,null,null,null,null,null,null);}
}
