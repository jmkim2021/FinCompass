# FinCompass Agent — 구현 상태

업데이트: 2026-09-22 (Asia/Seoul). Phase 11 final product audit/polish 완료.

## 최종 상태

- Golden 산식, Emergency Fund 분모, 관측/모델 cashflow, A–D 회계 불변식을 독립 감사했다.
- Financial Twin은 14개 metric과 trace의 source/fact/formula를 제공한다.
- 합성 상품 source를 `DEMO_PRODUCT_FIXTURE`로 분리했다.
- 동일 LoanOps pipeline에 불완전 4개 term과 완전 합성 4개 상품의 두 매칭 데모를 제공한다.
- OpenAI Responses API adapter를 기존 `AgentModelClient` 뒤에 추가했다. DEMO가 기본이며 실제 OpenAI runtime은 미검증이다.
- `TARGET_HOME_PRICE_SEARCH_V1` search, REST API, Agent tool, UI를 추가했다.
- curated Agent eval 30개(24 tool selection + 6 adversarial), frontend test 7개를 추가했다.
- demo bootstrap는 격리 compose project/volume과 localhost/127.0.0.1 CORS를 명시한다.
- V1–V15 migration은 수정하지 않았다. 신규 migration이나 backend production dependency는 없다.

## 실제 품질 게이트

- Backend `clean test bootJar`: **330/330 PASS, failure 0, error 0, skip 0**.
- Golden E2E: PASS. clean PostgreSQL에서 Flyway **V1→V15** 및 결과 JSON 생성.
- Frontend Vitest: **7/7 PASS**.
- Frontend TypeScript typecheck: PASS.
- Frontend production build: PASS, 44 modules.
- Agent tool selection: **24/24(100%)**.
- Unsupported Number Rate: **0/6(0%)** after rejection/fallback.
- OpenAI adapter WireMock: PASS. 실제 provider call: `REAL_LLM_RUNTIME_NOT_VERIFIED`.
- Open Banking DEMO_REPLAY/Public MyData replay: Golden E2E PASS. 실제 Open Banking: `LIVE_TEST_NOT_VERIFIED`.

## Golden 핵심값

- Twin: 자산 120M, 부채 30M, 순자산 90M, 관측 유출 3.4M, 모델 총유출 3.6M, 월여유 1.2M, 필수지출 2.2M, 비상자금 54.54545455개월.
- 700M scenario: 월납입 2,989,443.33원, 총이자 486,199,597.17원, 구매후 월여유 −1,789,443.33원.
- Complete matching: rank 1–4, score 45.0000/37.9102/34.3493/20.0000.
- Target search: 월여유 500K 제약에서 248.1M boundary, 주담대 138.1M, 월납입 699,732.41원.

## 보안·무결성

- DEMO mode는 외부 credential이 필요 없다. LLM mode에서만 `OPENAI_API_KEY`를 검증한다.
- secret은 환경변수 또는 git-ignored local profile만 사용한다. repository에는 빈 example만 둔다.
- 개인 원문/trace는 기존 envelope encryption과 owner scope를 유지한다.
- 원본 `/Users/jaemin/Desktop/Projects/Loanops`는 read-only baseline이다. 최종 HEAD/tracked/clean 상태는 release 직전 재확인한다.

## 남은 한계

- 실제 금융결제원 LIVE_TEST, 실제 Public MyData 정부 연동, 실제 OpenAI credential runtime 호출은 미검증이다.
- consumer API는 loopback demo owner 전용이며 production 다중 사용자 인증 UI가 아니다.
- 합성 eligibility/constraints는 pipeline/UX 검증용이며 실제 금융기관 조건이 아니다.
- 취득세·거래비용·미래 집값·소득상승을 추정하지 않는다.
- key rotation, retention/legal deletion, provider correction 운영 절차가 필요하다.
