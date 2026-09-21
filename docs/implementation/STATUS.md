# FinCompass Agent — 구현 상태

업데이트: 2026-09-21 (Asia/Seoul).

## 현재 상태

**Phase 3 Public MyData Mock + Provider Generalization 구현 완료. Backend 177/177, frontend typecheck/build, 실제 통합 JAR replay 검증 완료. Phase 4 미착수, 사용자 승인 대기.**

- Phase 2 `bc8a318`을 origin/phase/02-personal-finance에 push하고 main을 fast-forward한 후 origin/main에 push했다. force push/history rewrite 없음.
- 현재 작업 branch: `phase/03-public-mydata`. Phase 3 원격 push는 하지 않는다.
- ProviderLedger + V10으로 Open Banking-specific consent/run/raw FK coupling을 해결했다. 기존 legacy FK를 보존하며 PMD는 generic linkage만 사용한다.
- PublicMyDataProvider/Mock 구현/Real 미래 interface, deterministic persona 3개, IncomeEvidence/Employment typed model, provider별 consent, provenance 및 stale/conflict/current 정책 구현.
- 소득 증빙과 재직은 별도 scope/current이다. 소득은 PUBLIC_MYDATA_MOCK/DEMO_REPLAY이며 실제 정부 검증 소득으로 표현하지 않는다.
- 같은 명시적 demo user에 Main persona와 기존 OB 1계좌/1잔액/28거래를 연결했다. PDF Golden Twin 숫자를 맞추기 위한 데이터를 추가하지 않았다.
- 기존 상품 pipeline/API/frontend/dependency/V1–V9, ../Loanops read-only 보존. AI-Hub runtime provider/consumer API/UI/Financial Twin/계산/매칭/Agent 없음.
- 실제 금융결제원은 LIVE_TEST_NOT_VERIFIED. 실제 Public MyData는 의도적으로 미연동이다.

## 문서 및 이전 기준

- Phase 3 구조/공식 자료 확인 범위/Mock 계약/동의/DQ/보안/한계/Phase 4 handoff: `PUBLIC_MYDATA.md`.
- 실제 검증: `PHASE3_VALIDATION.md`.
- Phase 2 모델: `PERSONAL_FINANCE_MODEL.md`의 Phase 3 갱신 기록을 함께 참조.
- Phase 2 승인 baseline은 backend 131/131, frontend typecheck/build, replay canonical 1/1/28이다. 당시 검증은 `PHASE2_VALIDATION.md`에 유지한다.
- Phase 1 공식 OB 계약/설정/키 정책은 `OPEN_BANKING.md`, 금리 단위는 `RATE_UNITS.md`를 유지한다.

## 잔여 제약

실제 정부 API/전자서명/진위 검증 없음. 계좌 metadata 최초 관측 고정, 거래 correction/동일 관측값 ambiguity, key rotation, consumer 인증/retention/crash recovery는 운영 부채이다. 소득 current는 connection별이며 여러 연결의 소득 자동 결합/충돌 해소를 하지 않는다. 수집 후 미정규화 run은 명시적으로 normalize 재실행한다.

## 이후 구현 순서 — 승인 대기

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

**Phase 4 Financial Twin / Analytics는 시작하지 않았다. 사용자 승인 후에만 착수한다.**
