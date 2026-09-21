package com.loanops.fincompass.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static com.loanops.fincompass.agent.AgentModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Curated deterministic portfolio evaluation: exact cases and denominators are published in AGENT.md. */
class AgentCuratedEvaluationTest {
    private final DeterministicAgentModelClient model=new DeterministicAgentModelClient();
    private final NumericGrounding grounding=new NumericGrounding();private final ObjectMapper json=new ObjectMapper();

    @ParameterizedTest(name="{0}") @MethodSource("intents")
    void selectsExpectedIntentAndRequiredTool(String question,Intent intent,ToolName required){var plan=model.plan(request(question));assertThat(plan.intent()).isEqualTo(intent);assertThat(plan.tools()).contains(required);}

    static Stream<Arguments> intents(){return Stream.of(
        a("내 재무 상태 알려줘",Intent.FINANCIAL_OVERVIEW,ToolName.GET_FINANCIAL_SNAPSHOT),a("현금흐름을 보여줘",Intent.FINANCIAL_OVERVIEW,ToolName.GET_CASHFLOW_ANALYSIS),a("현재 부채 부담은?",Intent.FINANCIAL_OVERVIEW,ToolName.GET_DEBT_ANALYSIS),
        a("7억 아파트를 사고 싶어",Intent.HOME_PURCHASE,ToolName.SIMULATE_HOME_PURCHASE),a("주택 구매 후 현금흐름",Intent.HOME_PURCHASE,ToolName.RUN_STRESS_TEST),a("집을 사면 금리 충격은?",Intent.HOME_PURCHASE,ToolName.MATCH_LOAN_PRODUCTS),a("home buy scenario",Intent.HOME_PURCHASE,ToolName.SIMULATE_HOME_PURCHASE),
        a("대출 상품 검토",Intent.PRODUCT_REVIEW,ToolName.MATCH_LOAN_PRODUCTS),a("loan products",Intent.PRODUCT_REVIEW,ToolName.SEARCH_LOAN_PRODUCTS),a("상품 후보를 보여줘",Intent.PRODUCT_REVIEW,ToolName.GET_PRODUCT_MATCH_TRACE),a("내 대출 조건",Intent.PRODUCT_REVIEW,ToolName.MATCH_LOAN_PRODUCTS),
        a("위험 경고",Intent.OPPORTUNITIES,ToolName.GET_OPPORTUNITY_EVENTS),a("기회 신호",Intent.OPPORTUNITIES,ToolName.GET_OPPORTUNITY_EVENTS),a("opportunity events",Intent.OPPORTUNITIES,ToolName.GET_OPPORTUNITY_EVENTS),
        a("월 여유자금 50만원이면 집값 얼마까지",Intent.TARGET_SCENARIO_SEARCH,ToolName.SEARCH_HOME_PURCHASE_TARGET),a("조건을 맞추려면 가격은?",Intent.TARGET_SCENARIO_SEARCH,ToolName.SEARCH_HOME_PURCHASE_TARGET),a("target scenario",Intent.TARGET_SCENARIO_SEARCH,ToolName.SEARCH_HOME_PURCHASE_TARGET),
        a("안녕하세요",Intent.UNKNOWN,ToolName.GET_FINANCIAL_SNAPSHOT),a("도와줘",Intent.UNKNOWN,ToolName.GET_FINANCIAL_SNAPSHOT),a("무엇을 할 수 있어?",Intent.UNKNOWN,ToolName.GET_FINANCIAL_SNAPSHOT),
        a("재무 financial overview",Intent.FINANCIAL_OVERVIEW,ToolName.GET_CALCULATION_TRACE),a("주택 alternatives",Intent.HOME_PURCHASE,ToolName.COMPARE_PRODUCT_CANDIDATES),a("아파트 stress",Intent.HOME_PURCHASE,ToolName.RUN_STRESS_TEST),a("대출금리 비교 상품",Intent.PRODUCT_REVIEW,ToolName.SEARCH_LOAN_PRODUCTS));
    }

    @ParameterizedTest @ValueSource(strings={"보장 금리 9%","대출한도 888000000원","월 상환액 1234567원","상품 점수 99점","승인 확률 87%","가능 집값 543210000원"})
    void rejectsEveryAdversarialUnsupportedNumber(String output){var evidence=grounding.evidence("상품을 검토해줘",json.valueToTree(Map.of("known",700000000)),List.of());assertThat(grounding.supported(output,evidence)).isFalse();}

    private static Arguments a(String q,Intent i,ToolName t){return Arguments.of(q,i,t);}
    private ChatRequest request(String q){return new ChatRequest(q,null,null,null,null,null,null);}
}
