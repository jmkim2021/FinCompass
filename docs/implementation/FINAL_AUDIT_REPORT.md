# Phase 11 Final Product Audit

## 발견과 조치

- Emergency Fund의 2.2M 분모가 입력에는 있었지만 API/UI 설명이 약했다. metric·trace·UI·문서에 명시했다.
- 관측 유출 3.4M과 고정의무 0.2M, 모델 총유출 3.6M을 구분했다. 이중 차감은 없음을 invariant test로 확인했다.
- 합성 상품이 `REAL_PRODUCT_DATA`로 보일 수 있던 taxonomy를 `DEMO_PRODUCT_FIXTURE`로 교정했다.
- 불완전 상품의 안전한 no-score 동작은 유지하고, 별도 완전 합성 fixture로 end-to-end ranking을 증명했다.
- deterministic demo Agent만 있던 약점을 OpenAI Responses API adapter와 동일 Numeric Grounding gate로 보강했다.
- 700M의 부정적 결론 뒤 실행 가능한 질문을 지원하도록 Target Scenario Search를 추가했다.
- Agent eval 4/4·1 adversarial과 frontend runner 부재를 각각 24 tool cases/6 adversarial, Vitest 7개로 보강했다.
- demo bootstrap CORS가 환경값에 따라 브라우저 POST를 막을 수 있어 localhost와 127.0.0.1 기본값을 고정했다.

## 검증 증거

- backend 330/330, failure/error/skip 0.
- frontend 7/7, typecheck, production build.
- clean DB Flyway V1→V15 + Golden E2E.
- independent amortization formula, accounting invariants, target-search boundaries.
- WireMock OpenAI adapter. 실제 OpenAI runtime은 미검증.
- secret scan과 Loanops integrity는 최종 release 직전 결과를 `STATUS.md`와 최종 보고에 기록한다.

## 결론

FinCompass의 AI claim은 “LLM이 금융 계산을 한다”가 아니라 “LLM이 닫힌 도구를 orchestration하고 근거 있는 결과를 설명한다”로 제한된다. 계산, target search, candidate simulation, score/rank는 모두 versioned deterministic backend가 수행한다. 합성/실데이터, 결측/0, 관측/모델 지표, review/approval 경계가 UI와 trace에서 확인 가능하다.
