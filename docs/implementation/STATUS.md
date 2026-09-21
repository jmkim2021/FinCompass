# FinCompass Agent - 구현 상태와 승인 대기 계획

업데이트: 2026-09-21 (Asia/Seoul).

## 현재 상태

**Phase 0 초기 분석 완료. Phase 1 미착수, 사용자 승인 대기.**

- Loanops baseline `e9227c3491804b5892bc71e151608a044a26263c` 감사.
- PDF 28쪽 전체 텍스트/렌더링 확인 및 20개 요구사항 mapping 완료.
- 원본은 수정하지 않고, 테스트를 격리 복사본에서 실행: backend 47/47 통과, 실패/오류/skip 0; frontend typecheck/build 통과.
- Git 이력을 보존한 독립 baseline을 FinCompass 작업 폴더에 초기화. 기존 사용자 PDF 보존. 소스·migration·의존성·README/UI는 기존 내용 유지.
- 문서: PDF_REQUIREMENTS.md, BASELINE.md, STATUS.md, AGENTS.md.
- 새 금융 기능, 새 schema migration, 새 dependency, LLM/API 실연동, commit/push: 모두 수행하지 않음.

## Phase 1: 승인 후 정확히 구현할 범위

PDF §12 p.22의 Phase 1은 **Open Banking Integration**이다. Financial Twin/시뮬레이션/매칭부터 시작하지 않는다.

### 목표

Open Banking 개발/테스트 환경에서 OAuth와 계좌 잔액·거래 조회가 동작하고, 원문/출처/상태가 보존되는 최소한의 Personal Finance Gateway를 완성한다. 외부 연결 없이 같은 계약을 검증할 DEMO_REPLAY 경로를 함께 둔다. 아직 complete Financial Twin을 만들거나 금융 지표를 계산하지 않는다.

### 재사용

- 기존 Spring Boot/RestClient/Jackson/DI와 JPA/Flyway/PostgreSQL.
- PayloadHasher, 명시적 timeout, 정제된 오류, 별도 lifecycle 트랜잭션의 패턴.
- 기존 WireMock/PostgreSQL Testcontainers와 47개 regression suite.
- 기존 GET 운영 API 및 상품 pipeline은 유지. 새 개인 sync를 FSS/서울 source enum에 추가하지 않음.

### 예정 파일과 서비스 (현재 존재하지 않는 설계 제안)

1. `personal/gateway/PersonalFinanceProvider.java`: capability와 잔액/거래 조회 계약. card/loan은 supported/unsupported를 명시.
2. `personal/provider/openbanking/`: Properties, Client, Provider, TokenService, TokenStore, OAuth state 관리, 전용 request/response DTO.
3. `personal/provider/replay/`: 버전 고정된 sanitized fixture를 읽는 provider. `LIVE_TEST` 실패를 몰래 demo 성공으로 바꾸지 않음.
4. `personal/sync/`: source_sync_run 시작/성공/실패, raw 기록, cursor 진행/완료 상태. 응답 성공코드와 HTTP 성공을 따로 판단.
5. `personal/consent/` 및 최소 사용자/연결 식별 계약. provider 내부 fintech_use_num은 공용 DTO로 노출하지 않음.
6. `api/`의 OpenBankingConnectionController/DTO: 연결과 sync 상태만 노출. 기존 error envelope를 유지.
7. `application.yml`, `.env.example`: provider mode, endpoint/redirect/secret 참조, timeout, encryption key 참조. 비밀값 자체는 미포함. 보안 저장은 기존 스택/JDK를 우선 검토하고 새 dependency가 꼭 필요한 경우 이유/대안을 먼저 문서화.
8. 새 backend integration test와 `src/test/resources/fixtures/openbanking/`. 기존 product fixture와 분리.

### DB 변경 계획

승인받은 Phase 1에서만 **V8 이후 신규 migration**을 추가한다. 기존 V1~V7은 수정하지 않는다. V8 번호는 그 시점 최신 migration을 확인하고 확정한다.

최소 저장 범위는 financial_user, consent_grant, personal_source_connection/credential, source_sync_run, raw_openbanking_snapshot에 해당하는 개인 전용 모델이다. 암호화 token/key version/expiry, scope/동의기한, endpoint별 상태, sourceType/ingestionMode, request window/page cursor/completeness, raw hash/시각을 저장한다. `loanops` schema 내 별도 개인 테이블을 우선 검토하되 schema 분리 여부는 구현 시작 시 변경 계획에 명시한다. 완전한 account/transaction/card/loan canonical 적재는 Phase 2다.

raw 보존은 OAuth token payload를 무차별 저장하는 뜻이 아니다. token은 별도 보호 저장하고 금융 응답 원문은 개인 전용 접근 제한/암호화 정책으로 보관한다. 일반 로그·UI에는 원문을 노출하지 않는다.

### API 변경 계획

PDF에 연결 API의 URL은 고정되지 않았으므로 다음은 Phase 1의 제안 계약이다.

- `POST /api/v1/connections/open-banking/authorization`: state와 인증 시작 URL 생성.
- `GET /api/v1/connections/open-banking/callback`: state 검증 및 code 교환. code/token은 로그/응답에서 제거.
- `GET /api/v1/connections`: mode/capabilities/상태/마지막 sync/경고 조회.
- `POST /api/v1/connections/{id}/sync`: 잔액·거래 조회 수행, 연결 소유권/동의 확인.
- `GET /api/v1/source-sync-runs/{id}`: 정제된 실행·완전성 상태.

기존 운영 API의 GET-only 정책과 DTO는 보존한다. 새 POST 경로만 필요한 origin/method 정책을 추가하고 테스트한다. connection/user 식별자를 임의 전달해 다른 사용자의 정보를 조회하지 못하도록 한다. PoC의 local test-user 경계와 실제 운영 인증은 구분해서 문서화한다.

### Phase 1 구현 순서와 검증

1. 작업 트리/원본 read-only와 baseline commit을 확인하고 파일/schema/API 변경 목록 작성.
2. 공식 developer 환경의 client/redirect/scope, balance/transaction 계약을 확인. 비밀값은 환경변수 또는 ignored local config로만 제공.
3. Provider/Mode/Capability와 보안 저장·동의 계약부터 구현.
4. OAuth state → code exchange → access/refresh/expiry lifecycle 및 failure 테스트.
5. balance endpoint → transaction endpoint 순서로 실제 계약에 매핑. cursor 마지막 페이지 확인까지 수집하고 실패하면 incomplete를 보존.
6. 동일 response contract의 demo replay, sync 상태 및 metadata-only API 구현.
7. WireMock에서 토큰 만료/refresh 실패/HTTP200 오류/timeout/페이지 25·26건/반복 cursor/중간 실패/미지원 capability/redaction/동의·소유권 오류를 검증.
8. 전체 기존+신규 backend suite, frontend typecheck/build, git diff 확인 후 문서 갱신.
9. 실제 developer endpoint로 OAuth와 balance/transaction 성공을 확인하고 민감정보 없는 실행 증거 기록. 계정/권한이 없어 수행 못하면 'contract/replay 검증 완료, LIVE_TEST 미완료'로 구분하며 Phase 1 전체 완료를 선언하지 않는다.

**Phase 1 완료 기준**: 외부 개발 환경의 OAuth/잔액/거래 성공, token failure 처리, endpoint별 raw/provenance/완전성, 명시적 replay/capability 구분, 기존 47개 회귀 통과, 비밀값 미노출. 개인 canonical 전체·Twin·상환/주택/스트레스·Matching·Opportunity·Agent·consumer 전체 UI는 이 단계에 포함하지 않는다.

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

## 우선 해결할 불명확성

1. Phase 1: developer 계정/redirect/scope 및 실제 card/loan capability, token key 관리, 개인 raw 보존기간과 test user 경계.
2. Phase 2~4: transaction dedupe key, 정상 음수잔액, cashflow 집계기간/필수지출, 카드청구 중복, freshness SLA, 3개 persona 상세.
3. Phase 5: 기존 대출 상환조건, 세후/검증 소득 선택, 금리 단위, 부대비용/비상금, 고정금리 shock·연기 가정.
4. Phase 6: 상품 결측 허용정책, 상환방식 매핑, 다중 publication 고정 방식, score 세부식/동점/freshness, 검토 후보가 없는 정상 경로.
5. Phase 8~9: 숫자 출처 검사와 모델/보안/응답시간 기준, 실제 출처 상품의 offline replay 자료 확보.

이 문서는 승인 요청을 위한 구체적인 구현 계획이다. **이번 작업 종료 후 사용자 승인 없이 Phase 1 코드를 작성하거나 migration/dependency를 추가하지 않는다.**
