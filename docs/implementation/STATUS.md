# FinCompass Agent — 구현 상태

업데이트: 2026-09-21 (Asia/Seoul).

## 현재 상태

**Phase 1 구현 및 로컬 검증 완료. Phase 2 미착수, 승인 대기.**

- 사용자 승인 변경: 개발자 앱/credential/redirect/테스트 계좌 미준비로 실제 LIVE_TEST를 blocker로 두지 않음. adapter + WireMock + DEMO_REPLAY까지 이번 완료 범위. 실제 호출은 **LIVE_TEST_NOT_VERIFIED**.
- Phase 0 baseline commit `c1d729d` 생성, SSH origin `git@github.com:jmkim2021/FinCompass.git`의 main에 최초 push 완료. 기존 `e9227c3` 이전 LoanOps history 보존.
- 개발 branch: `phase/01-open-banking`. main에서 구현하지 않음. force push 없음. Phase 1 branch 원격 push는 아직 수행하지 않음.
- 기존 Loanops 원본 read-only 유지. 기존 상품 pipeline/API/frontend/dependency/V1~V7 보존.
- 신규 module: `personal/openbanking` provider/client/token/store/mapper/sync/manual runner 및 두 mode.
- V8: 연결/state/run/raw/evidence/checkpoint 6개 additive table. Phase 2 canonical 모델 아님.
- `AnnualInterestRate` 명시적 percent → decimal fraction 변환과 테스트 추가. 기존 상품 rate 의미는 변경하지 않음.

## 실제 검증

- Backend full test: **89개 통과**, 실패/오류/skip 0. 기존 47개 + 신규 42개.
- WireMock 검증 완료: OAuth/refresh/만료/동시 refresh, balance, pagination(25건 경계/복수/마지막/반복/상한), timeout, partial failure, overlap/incremental/dedupe, 소유권·동의·mode, raw redaction.
- DEMO_REPLAY 검증 완료: 공통 raw→mapping→provenance 경로, 같은 입력의 hash/identity 재현, Spring 기본 mode wiring. 빌드 JAR도 임시 PostgreSQL에서 잔액 1 page/1건, 거래 2 page/28건 SUCCESS, 카드·대출·리스 UNSUPPORTED 확인.
- Frontend `npm run typecheck`, `npm run build` 통과. frontend unit/E2E runner 없음.
- 실제 금융결제원 LIVE_TEST API 호출 **미실시**. WireMock success를 실연동 success로 표시하지 않는다.
- 실행 명령/분류별 결과/증거: `PHASE1_VALIDATION.md`.

## Phase 1 최종 설계와 구현 범위

`OPEN_BANKING.md`에 architecture, 공식 계약 출처, 환경변수, 실행법, OAuth/sync lifecycle, pagination/dedupe, 보안, 미지원 capability, 기술부채를 기록했다. `RATE_UNITS.md`에 금리 단위를 기록했다.

Phase 0의 HTTP connection API는 제안이었다. 구현은 로컬 운영자용 manual profile + Java service interface로 제한했다. 실제 사용자 인증이 없는 기존 서버에 personal-data endpoint를 열지 않았으며 consumer UI는 추가하지 않았다. ownerRef는 운영자 가명으로, 다중 사용자 인증 체계가 아니다.

LIVE_TEST 환경에는 endpoint/credential/등록 redirect/기관 prefix/테스트 fintech_use_num, 안정적인 암호화 key와 별도 DB를 제공해야 한다. 공개 명세와 같은 계약이면 config 주입으로 검증할 수 있다. 등록/권한/상세 OAuth 계약의 실환경 확인은 잔여 작업이다.

정상 checkpoint는 실패로 교체하지 않는다. 중간 페이지 실패는 PAGINATION_INCOMPLETE + complete=false. 카드/대출/리스는 사용권한 미확인이므로 명시적 UNSUPPORTED. demo는 합성 fixture이며 LIVE_TEST 오류의 fallback으로 쓰지 않는다.

## Phase 2에 넘길 제약

- sync complete는 capability/요청 기간 범위만 보장한다. Financial Twin 완전성을 뜻하지 않는다.
- 거래 복합 hash는 ingestion 관측값 dedupe이며 은행 원장 ID가 아니다. 동일 관측값의 실거래 구별/정정/새 연결/키 회전은 Phase 2에서 정책화.
- 정상 음수 잔액 허용, observation timestamp와 sync timestamp 구별. freshness SLA는 향후 DQ에서 확정.
- raw body는 암호화·민감값 제거. malformed body는 hash+omission marker만 보존되어 정확한 replay 불가.
- 운영 인가/callback/계좌목록/철회·retention/키 회전/crash RUNNING 복구는 추가 운영 범위. 실제 개인정보 장기 운영 준비 완료라는 뜻이 아니다.
- 상품 missing fields/catalogue reproducibility와 PDF 설명용 금융 예시는 Phase 0 원칙 유지.

## Phase 2 이후 구체적 구현 순서

### Phase 2 - Personal Canonical Model 및 DQ

- Phase 1 raw를 account/balance/transaction/card/loan/payment/investment 모델로 정규화. 카드/대출 미지원은 DEMO fixture 또는 비활성화를 명시.
- 거래 window overlap/dedupe·정정 정책, source sync cursor, 기관 실패 격리, freshness, 개인 DQ issue/lineage 구현.
- DB: 개인 canonical/quality/lineage 및 snapshot completeness 메타데이터 추가. 아직 계산된 Twin 승격은 Phase 4지만 complete dataset gate를 여기서 제공.
- 완료: 동일 raw mapping 재현, 중복/누락/정정/잔액/대출 mismatch, incomplete pagination 차단 및 마지막 정상 dataset 보호 테스트.

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

Phase 2의 personal canonical/DQ, Financial Twin, 계산/Matching/Agent/consumer UI는 구현하지 않았다. **사용자 승인 후 Phase 2를 시작한다.**
