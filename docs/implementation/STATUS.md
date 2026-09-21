# FinCompass Agent — 구현 상태

업데이트: 2026-09-21 (Asia/Seoul).

## 현재 상태

**Phase 2 Canonical Personal Finance + Data Quality 구현 완료. Backend 131/131, frontend typecheck/build, 실제 JAR replay 검증 완료. Phase 3는 미착수이며 별도 승인 대기.**

- 사용자 승인에 따라 Phase 1 `0a2fdb2`를 origin/phase/01-open-banking에 push하고, main을 fast-forward한 후 origin/main에 push했다. Phase 0 `c1d729d` 및 이전 history는 보존했다. force push 없음.
- 현재 개발 branch는 `phase/02-personal-finance`. Phase 2 원격 push는 하지 않는다.
- ../Loanops는 read-only. 기존 상품 pipeline/API/frontend/dependency 및 V1–V8은 보존한다.
- 실제 KFTC 호출은 계속 **LIVE_TEST_NOT_VERIFIED**. 사용자 지시에 따라 credential 미준비는 이번 단계 blocker가 아니다.

## Phase 2 결과

- PersonalFinanceProvider와 Open Banking evidence adapter, canonical 11개 domain concept, 명시적 DEMO/DEVELOPER_TEST consent, 중앙 provenance 구현.
- V9에서 16개 canonical/DQ/lineage/current table 추가. 실제 생성 대상은 계좌/잔액/거래이며 loan/card/investment/income rows는 만들지 않는다.
- 6개 필수 DQ rule 및 안전한 current promotion 구현. incomplete/invalid/stale는 기존 정상 current를 대체하지 않는다. 최신 실패와 이전 정상 상태를 함께 노출한다.
- stale 기본 24h, policy/threshold 저장, 조회 시 freshness 재평가. 거래 관측 identity는 Phase 1 HMAC 규칙 그대로 사용.
- 서비스 API와 local manual replay profile만 추가. consumer HTTP API/UI, Financial Twin, Public MyData Mock, 계산/매칭/Agent는 구현하지 않았다.
- 구현 계약·mapping·정밀도·NULL·consent·암호화·known limits·Phase 3 handoff: `PERSONAL_FINANCE_MODEL.md`.
- 실제 backend/frontend/migration/replay/security/integrity 결과: `PHASE2_VALIDATION.md`.

## 이전 검증과 남은 경계

Phase 1은 backend 89/89(기존 47 + 신규 42), frontend typecheck/build, WireMock OAuth/refresh/balance/transaction/pagination/timeout/partial, 실제 replay JAR를 검증했다. 상세는 `PHASE1_VALIDATION.md`, 계약/설정은 `OPEN_BANKING.md`, 금리 단위는 `RATE_UNITS.md`에 보존한다.

거래 composite identity는 은행 원장 ID가 아니다. 동일 관측값의 실거래 구별/정정/재연결/키 회전은 자동 해결하지 않는다. 계좌 metadata는 최초 관측에 고정된다. provider Java boundary와 별개로 현재 relational connection/evidence FK는 Open Banking에 묶여 있어 Phase 3에서 additive 확장이 필요하다.

운영자 가명은 consumer 인증을 대체하지 않는다. canonical 동의 철회와 upstream OAuth 폐기는 별개이다. retention/파기/key rotation/crash recovery는 운영 부채로 남는다. 이 단계 완료는 개인정보 장기 운영 준비 완료를 뜻하지 않는다.

## 이후 구현 순서 — 승인 대기

### Phase 3 - Public MyData Mock / 소득 evidence / offline fixture

- 공식 묶음정보 계약 버전과 field mapping 작성. Mock provider와 evidence/consent 저장, PUBLIC_MYDATA_MOCK 고정.
- verified annual income과 거래 기반 observed cashflow 분리. Real provider는 향후 교체 interface만.
- 3개 통합 persona와 AI-Hub offline loader 경계 확정. AI-Hub를 runtime 동일 개인으로 합성하지 않음.
- 완료: 소득 연도/asOf/출처 trace, mock 경계, 최소정보/동의 테스트.

### Phase 4 - Financial Twin와 Analytics

- 6개 지표 정의, 소득/지출 중복방지·통화·월별 관측기간·0분모·반올림 정책 버전 확정.
- immutable financial_snapshot/metric/lineage, Clock 주입, complete 승격과 read API 3개 구현.
- 완료: 3개 persona의 golden metrics, 동일 입력·규칙에 동일 계산 내용, freshness/불완전 데이터 처리와 중요 KPI trace.

### Phase 5 - Loan / Home / Stress / Compare

- 먼저 repayment 순수 엔진과 독립 golden → home 자금조달·비용/가용현금 검증 → stress grid/복합 → 대안 A~D 순서.
- scenario/assumption/result 저장과 snapshot/rule/asOf 고정. PDF §11의 scenario POST API 4개 구현.
- 완료: 7억 주택 통합 persona의 명시적 가정에 대한 golden, 금리 단위·0%·만기금·최종 rounding·shock 대상·base 비변경·12개월 연기 비교 검증.

### Phase 6 - Product Matching

- 먼저 raw→canonical 상품 필드의 실제 가용성 audit와 NULL 정책 확정. 목적/기간/한도/자격은 원천에 있는 값만 확장.
- immutable candidate pool + product/term/publication manifest → hard filter → 동일 엔진 simulation/stress → 35/25/20/10/10 component ranking → trace 순서.
- DB: matching 4개 모델과 필요한 상품 확장. API: POST/GET loan-product-matches. 부족 후보는 점수/rank를 꾸미지 않음.
- 완료: 동일 입력/rule/publication에서 Top-K/점수/trace 재현, non-serving/critical missing 제외, 동점 처리, candidate별 동일 계산 및 단일 publication 착각 방지 테스트.

### Phase 7 - Opportunity

- Twin 갱신과 DataChange를 입력으로 liquidity/rate/refinancing 검토 신호, idempotent event 저장과 GET API.
- 완료: threshold 경계, 중복/해제, 잘못된 상품 간 비교 방지, 근거와 ruleVersion. 외부 메시지 전송/실제 대출 신청 없음.

### Phase 8 - Agent + Audit

- 결정론적 tool 14개와 typed schema를 먼저 고정; mock model로 tool-selection/숫자 출처/trace 평가; 그 뒤 provider 연결.
- agent_run/tool_call/result evidence 저장, chat/trace API. Agent는 계산·상품 rank 생성·승인 추정 금지.
- 완료: 대표 질문과 실패 사례에서 unsupported number 0, 핵심 KPI trace 100%, 동일 저장 결과 replay. 외부 LLM 없이 CI 가능.

### Phase 9 - Consumer UI 및 통합 Demo

- 기존 Admin을 보존하고 Data Connections → Twin → Chat → Scenario → Candidates → Stress/Alternatives/Opportunity → Evidence 흐름을 추가.
- 사용자 노출 title/README/헤더/agent 명칭을 FinCompass Agent로 정리. Java package 이름은 유지.
- network-off DEMO_REPLAY와 LIVE_TEST 표시, 모든 source badges, missing/assumptions/limitations 표시.
- 완료: 3개 persona와 7억 주택 demo, 브라우저 흐름/오류 상태 검증, frontend 테스트 공백 보완, 보안/trace/latency 검토, README와 demo 증거. 이미지나 그럴듯한 수치만으로 완료 선언 금지.

## 다음 승인 경계

**Phase 3 Public MyData Mock / 소득 evidence / offline fixture는 시작하지 않았다. 사용자 승인 후에만 착수한다.**
