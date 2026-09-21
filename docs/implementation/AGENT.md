# FinCompass Agent

## 실행 경계

`AgentModelClient`는 공급자 중립 경계다. 기본 `FINCOMPASS_AGENT_MODE=DEMO`는 CI와 오프라인 데모에서 결정론적으로 의도를 분류한다. `LLM`은 OpenAI Responses API용 `OpenAiAgentModelClient`를 선택한다. 외부 자격증명은 LLM mode를 명시적으로 선택할 때만 검증한다. 등록된 15개 도구는 Financial Twin, Simulation, Product Matching, Opportunity, Target Scenario Search 서비스만 호출한다.

## 숫자 근거와 실패 처리

최종 설명의 모든 숫자 토큰은 사용자 입력 또는 직렬화된 도구 결과의 숫자 집합과 대조한다. 소수 비율은 백분율 표현도 같은 근거로 허용한다. 모델 초안에 근거 없는 숫자가 하나라도 있으면 전체 초안을 버리고 결정론적 템플릿으로 대체하며 `UNSUPPORTED_NUMBER_FALLBACK`을 기록한다. 필수 주택가격·현금기여액·주담대금액·연이율·기간이 없으면 값을 추정하지 않고 `NEEDS_INPUT`을 반환한다. 상품 후보는 승인·보장·최적 상품으로 표현하지 않는다.

## 감사 추적

V15의 `agent_run`과 `agent_tool_call`은 append-only이며 payload는 기존 personal-finance envelope encryption으로 암호화한다. 추적에는 provider/version, 질의, 시간, params/result hash, snapshot/scenario/match ID, rule version, assumptions, warnings, limitations, 허용 숫자 근거와 최종 설명을 저장한다. API는 `POST /api/v1/agent/chat`, `GET /api/v1/agent/runs/{id}/trace`다.

## 실제 측정

Phase 11 curated eval의 Tool Selection Accuracy는 **24/24(100%)**다. invented rate/limit/payment/score/approval probability/house-price target 6개 adversarial 초안은 모두 rejection/fallback되어 최종 Unsupported Number Rate가 **0/6(0%)**다. 기존 integration test는 Golden trace 필수 필드 **1/1**, missing-input clarification, 명시적 tool failure 처리를 검증한다. 이는 결정론적 curated set 결과이며 일반화된 모델 품질 지표가 아니다.

LLM에는 닫힌 intent/tool 목록과 deterministic tool 결과만 제공한다. LLM은 금융 숫자를 계산하거나 score/rank를 수정하지 않으며 provider 초안도 동일한 `NumericGrounding` 검사를 거친다. WireMock adapter 테스트는 통과했지만 실제 OpenAI credential 호출은 `REAL_LLM_RUNTIME_NOT_VERIFIED`다.

HTTP 계약은 OpenAI 공식 [Create a model response](https://developers.openai.com/api/reference/cli/resources/responses/methods/create) 명세의 `POST /v1/responses`를 따른다. 기본 model은 `gpt-5.6-luna`이며 환경변수로 교체할 수 있다.
