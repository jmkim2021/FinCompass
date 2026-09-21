# FinCompass Agent

## 실행 경계

`AgentModelClient`는 공급자 중립 경계다. 기본 `DEMO_REPLAY` 구현은 CI와 오프라인 데모에서 결정론적으로 의도를 분류하고 근거가 있는 초안만 반환한다. 외부 LLM 자격증명은 빌드·테스트·데모에 필요하지 않다. 에이전트는 임의 SQL이나 코드 실행 도구를 제공하지 않으며, 등록된 14개 도구는 Financial Twin, Simulation, Product Matching, Opportunity 애플리케이션 서비스만 호출한다.

## 숫자 근거와 실패 처리

최종 설명의 모든 숫자 토큰은 사용자 입력 또는 직렬화된 도구 결과의 숫자 집합과 대조한다. 소수 비율은 백분율 표현도 같은 근거로 허용한다. 모델 초안에 근거 없는 숫자가 하나라도 있으면 전체 초안을 버리고 결정론적 템플릿으로 대체하며 `UNSUPPORTED_NUMBER_FALLBACK`을 기록한다. 필수 주택가격·현금기여액·주담대금액·연이율·기간이 없으면 값을 추정하지 않고 `NEEDS_INPUT`을 반환한다. 상품 후보는 승인·보장·최적 상품으로 표현하지 않는다.

## 감사 추적

V15의 `agent_run`과 `agent_tool_call`은 append-only이며 payload는 기존 personal-finance envelope encryption으로 암호화한다. 추적에는 provider/version, 질의, 시간, params/result hash, snapshot/scenario/match ID, rule version, assumptions, warnings, limitations, 허용 숫자 근거와 최종 설명을 저장한다. API는 `POST /api/v1/agent/chat`, `GET /api/v1/agent/runs/{id}/trace`다.

## 실제 측정

Phase 8 테스트의 curated intent 4종은 모두 예상 intent를 선택했다. Tool Selection Accuracy는 **4/4**, unsupported-number fixture의 Unsupported Number Rate는 fallback 뒤 **0/1**, Golden trace 필수 필드 완전성은 **1/1**, 명시적 tool-failure fixture 처리율은 **1/1**이다. 이 값은 작은 결정론적 테스트 세트의 결과이며 일반화된 모델 품질 지표가 아니다.
