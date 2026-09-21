# FinCompass Agent Engineering Rules

## 범위와 승인

- 이 파일은 FinCompass Agent 저장소 전체에 적용한다. 사용자에게 한국어로 보고한다.
- 먼저 `docs/implementation/STATUS.md`, `BASELINE.md`, `PDF_REQUIREMENTS.md`를 읽는다.
- 제품/아키텍처 source of truth는 `docs/FinCompass_Agent_Project_Plan.pdf` 전체 28쪽이다. 본문뿐 아니라 표/도표도 확인한다.
- 현재 승인 범위는 Phase 2 Canonical Personal Finance + Data Quality까지이다. **Phase 3는 사용자 승인 전 시작하지 않는다.**
- 승인된 phase만 구현한다. PDF/문서에 적힌 후속 기능은 현재 실행 허가가 아니다. 명시적 사용자 지시와 승인 범위를 우선한다.
- `../Loanops`는 영구 read-only baseline이다. 그 안에서 편집·빌드·테스트·format·reset·commit을 하지 않는다.
- 구현/문서 변경은 이 FinCompass 저장소에서만 한다. 원본 `.env`, token, DB volume, build cache를 복사하지 않는다.
- 변경 전 git status/diff와 untracked 파일을 확인하고 사용자 작업을 덮어쓰지 않는다. 명시적 요청 없이 원격 push하지 않는다.

## 제품과 기존 LoanOps 보존

- 사용자 제품명은 FinCompass Agent, 내부 trusted product-data subsystem은 LoanOps이다. `com.loanops` 일괄 rename 금지.
- 기존 `source → normalization → publication/quality/quarantine → serving → DataChange`와 Admin UI를 보존한다.
- `source`를 `ingestion`으로 옮기거나 별도 `quality` 패키지를 중복 만드는 불필요한 리팩터링을 하지 않는다.
- Modular Monolith, 기존 Java/Spring/React/JPA/Flyway/PostgreSQL을 유지한다. 제2 ORM/migration 도구·전체 stack upgrade 금지.
- dependency 추가 전에 현재 라이브러리와 대안을 검토한다. microservices/Kafka/Kubernetes/불필요한 인프라를 추가하지 않는다.
- 기존 운영 API는 read-only를 유지한다. 과거 LoanOps Phase 5의 GET-only/AI 금지는 승인된 FinCompass 신규 API까지 영구 금지하는 규칙이 아니다.

## 데이터 무결성과 경계

- 상품은 기존 Product Serving에서, 개인 데이터는 별도 Personal Finance Gateway에서 들어온다. 결합은 명시적 analytics/simulation/matching 서비스에서 한다.
- 공식 상품 응답의 원본/해시/시각과 정확한 source run을 보존한다. 실패/격리/불완전 새 데이터가 마지막 정상 serving을 대체하면 안 된다.
- RUNNING과 최종 성공/실패를 외부 호출의 예외로 지워지지 않는 lifecycle transaction으로 보존한다. HTTP 성공과 source 성공은 별개이다.
- 정규화 invocation과 publication pointer/change 기록은 각각 원자적으로 처리한다. 품질 evidence를 거절 전에 저장한다.
- FSS 상품과 서울시 정책연계 대출 집계는 의미가 다르다. 서울 관측을 FSS 상품이나 개인 대출금리로 직접 연결하지 않는다.
- 기관 매핑은 source identity 또는 명시적 mapping만 허용한다. 불확실 값은 UNMAPPED; fuzzy matching 금지.
- 상품 business-identical 입력은 NO_CHANGE, provenance-only 변화는 pointer를 다시 쓰지 않는다. 완전성 근거 없는 REMOVED 추론 금지.
- FSS 금리 min/max는 순서를 지키고 각각/optional average는 비음수이다. FSS average를 min/max 사이로 강제하지 않는다. 서울은 min <= avg <= max.
- 과거 quality/calculation/score 정책과 결과를 제자리 재계산하지 않는다. version과 입력/결과를 보존한다.
- 개인 pagination 불완전 상태는 complete Financial Twin 승격을 차단한다. dedupe/overlap/freshness/기관 실패 격리와 metric lineage를 검증한다.
- 검증 연소득 evidence와 거래에서 관측한 월 현금흐름은 별도 저장하고 계산에 쓴 소득 기준을 기록한다.
- REAL_PRODUCT_DATA, OPEN_BANKING_TEST, PUBLIC_MYDATA_MOCK, USER_INPUT, DERIVED, AIHUB_OFFLINE을 구분한다.
- LIVE_TEST와 DEMO_REPLAY를 별도 mode로 표시한다. fixture 원산지도 기록하며 실패한 live 호출을 몰래 fixture 성공으로 바꾸지 않는다.
- 공공 MyData는 MVP에서 계약 대응 Mock이다. AI-Hub는 offline 테스트/평가 전용이며 런타임 사용자 연결/임의 개인 join 금지.

## 계산, 매칭, Agent

- 금융 숫자는 사용자 입력·원천 데이터·결정론적 backend만 생성한다. LLM/프런트엔드에 이자/상환/재무지표/점수 계산을 맡기지 않는다.
- money/rate 단위와 BigDecimal precision/rounding, Clock, 0분모/결측 정책을 명시하고 golden test로 검증한다.
- scenario/result에 financialSnapshotId, 해당 productPublication 및 실제 normalized record IDs, assumptions, calculatedAt, source timestamps, calculationRuleVersion, warnings/limitations를 기록한다.
- 현재 PublicationRun은 전체 카탈로그 snapshot이 아니다. 매칭 candidate pool과 상품/term별 provenance를 고정한다.
- 자격/금액/기간/금리/상환방식 결측을 추정해서 채우지 않는다. 후보는 MATCHED_FOR_REVIEW/FILTERED_OUT/INSUFFICIENT_DATA로 구분한다.
- Hard Filter 뒤에만 deterministic score를 적용한다. 초기 가중치 35/25/20/10/10을 설정으로 관리하고 component/weight/total/rank/scoreRuleVersion을 저장한다.
- Agent는 typed service tools를 호출하고 가정/한계/결측을 설명한다. arbitrary SQL, 독립 숫자 생성, 순위 덮어쓰기, 승인/가입 보장, 최고/최적 상품 표현 금지.
- agent_run/tool_call에 질문, 모델/provider/version, 시간/status, 정제된 params, 결과 hash와 재생 가능한 저장 참조, 최종 설명을 기록한다.
- 독자 신용평가, 실제 금융 신청/송금 자동화, 투자 최적화, Monte Carlo, 근거 없는 예측 ML은 MVP에서 제외한다.

## 보안, API, 테스트와 보고

- API key/client secret/access·refresh token/개인 테스트 식별자는 commit·로그·일반 DTO에 넣지 않는다. 환경/ignored config와 보호 저장소를 사용한다.
- 개인 raw/evidence는 상품 raw 공개 정책을 복사하지 말고 최소수집·암호화·접근통제·보존기간을 명시한다. 불필요한 주민번호/행정문서 이미지 금지.
- 사용자/동의/연결 소유권을 검증한다. OAuth state·만료·refresh 실패를 처리한다. callback code와 token도 redaction 대상이다.
- JPA entity 대신 dedicated DTO를 반환하고 기존 error envelope와 bounded deterministic pagination을 따른다. 새 POST의 CORS는 필요한 경로/origin만 허용한다.
- 기존 Flyway migration은 수정하지 않는다. 새 migration은 해당 phase 승인 후 추가한다. H2 대신 PostgreSQL Testcontainers를 사용한다.
- 현재 회귀 기준: backend 131개 (Phase 0 47개 + Phase 1 42개 + Phase 2 42개), frontend typecheck/build. frontend에 현재 자동 테스트 runner가 없다는 사실을 숨기지 않는다.
- 새 기능은 mapping/pagination/dedupe/stale/promotion-block, metric/repayment/home/stress/compare golden, matching/missing-data/reproducibility, agent 숫자/trace/failure를 단계별 검증한다.
- CI 핵심 테스트는 외부 LLM/API에 의존하지 않고 WireMock/fake model/명시적 fixture를 사용한다.
- 매 phase: 관련 코드 조사 → 재사용/파일·schema·API 변경계획 → 구현 → 필요한 테스트 실행 → git diff 검토 → STATUS 갱신 → 변경·명령·실제 결과·한계·다음 단계 보고.
- 테스트를 실행하지 않고 통과했다고 말하지 않는다. 실패/skip/미검증 범위를 구분하며 실패를 숨기거나 테스트를 약화하지 않는다.
