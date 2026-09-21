# FinCompass Final Audit Plan

업데이트: 2026-09-22. 대상 브랜치 `phase/11-final-polish`.

## 확인된 강점

- LoanOps의 raw -> normalization -> quality/quarantine -> publication -> serving 흐름과 V1-V10 이력을 보존했다.
- Financial Twin, 시뮬레이션, 매칭, Opportunity, Agent trace가 immutable 입력과 rule version을 저장한다.
- 금융 계산은 `BigDecimal` 기반 결정론적 backend에 있고 Agent의 숫자 grounding과 결측 데이터 안전 정책이 존재한다.
- Golden 7억원 시나리오, 스트레스, A-D 대안, clean-DB E2E가 이미 하나의 통합 흐름으로 연결되어 있다.

## Correctness 및 의미 우려

- 비상자금 54.54545455개월의 분모 2.2M은 `MONTHLY_ESSENTIAL_EXPENSE` 사용자 입력이나, Golden 문서와 UI에서 출처·공식이 충분히 드러나지 않는다.
- `OBSERVED_MONTHLY_OUTFLOW=3.4M`은 거래에서 관측된 생활비 2.8M + 부채상환 0.6M이고, 별도 모델 고정의무 0.2M은 월 여유자금에만 추가된다. 이를 총 월지출처럼 보이면 의미가 틀어진다.
- 합성 상품 fixture가 pipeline을 통과했다는 이유로 `sourceType=REAL_PRODUCT_DATA`를 사용한다. 이는 원산지 의미 오류다.
- 대안 C/D는 엔진상 회계적으로 일관되지만 이를 고정하는 자산-부채=순자산, 조달항등식, timeline invariant 증거를 강화해야 한다.
- 상환액 테스트는 생산 계산기와 독립된 폐쇄형 공식 기준 테스트가 필요하다.

## 데모 약점과 주장 증거 부족

- 기존 상품은 안전하게 `INSUFFICIENT_DATA`이나 완전한 Hard Filter -> Simulation -> Stress -> Score -> Rank 데모가 없다.
- `AgentModelClient`의 외부 LLM 구현이 없어 AI Agent 주장이 deterministic demo에만 의존한다.
- 7억원 및 A-D가 모두 음수인 뒤 사용자가 조건을 역산할 deterministic Target Scenario Search가 없다.
- Agent 평가가 4개 intent/1개 adversarial case에 그치고 frontend 자동 테스트 runner가 없다.
- Target Search 및 상품별 score/evidence를 UI에서 보여 주는 경로, 포트폴리오 screenshot 증거가 없다.

## 구현 변경 계획

1. Golden 입력에 essential expense를 공식 입력으로 노출하고 Twin/API/UI trace에서 observed outflow, modeled fixed obligation, total modeled outflow를 구분한다.
2. 독립 상환 공식과 A-D 회계 invariant 테스트를 추가하고 실제 Golden 산출물과 문서를 동기화한다.
3. 상품 출처를 `REAL_PRODUCT_DATA`와 `DEMO_PRODUCT_FIXTURE`로 분리한다. 기존 불완전 demo를 유지하면서 완전한 3-5개 합성 상품을 동일 LoanOps pipeline으로 추가한다.
4. 완전 fixture에서 deterministic score/rank/payment/stress/provenance를 통합 검증하고 UI에서 Review Candidate로 표시한다.
5. 기존 `AgentModelClient` 뒤에 OpenAI Responses API 어댑터를 추가한다. 기본은 credential-free DEMO, LLM 모드에서만 key/config를 검증하며 HTTP는 WireMock으로 테스트한다.
6. HOUSE_PRICE 대상 bounded binary search 서비스, API, Agent tool, UI를 추가한다. 사용자가 제공한 explicit constraints만 사용하고 결과를 simulation boundary로 표현한다.
7. 20-30개 이상의 curated Agent 평가를 추가해 tool selection, 숫자 grounding, clarification, trace, failure behavior를 정확한 분수로 기록한다.
8. Vitest + React Testing Library의 최소 구성을 추가해 결측값, source badge, 후보 점수, warning, trace, Target Search 라벨을 검증한다.
9. Opportunity의 정직한 active/no-active 상태와 demo provenance, README/API/구현 문서, demo bootstrap UX를 정리한다.
10. 전체 backend regression, frontend tests/typecheck/build, clean DB E2E, secret scan, LoanOps 무결성 확인 후에만 branch를 push하고 main을 fast-forward한다.

## 변경하지 않는 범위

- LoanOps baseline, 기존 migration V1-V15, modular monolith, 금융 계산의 결정론적 서비스 경계는 유지한다.
- 실제 Public MyData 연동, 실제 대출 신청/승인, 규제 DSR 판정, forecasting/ML ranking은 추가하지 않는다.
