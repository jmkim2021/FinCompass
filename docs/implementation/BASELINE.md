# FinCompass Agent - LoanOps 감사 및 Regression Baseline

감사일: 2026-09-21 (Asia/Seoul). 기능 구현·기존 소스 수정·새 migration·의존성 추가 없음.

## 1. 감사 대상과 작업 트리

- 읽기 전용 원본: `/Users/jaemin/Desktop/Projects/Loanops`.
- 대상: `/Users/jaemin/Desktop/Projects/FinCompass Agent`.
- 기준 commit: `e9227c3491804b5892bc71e151608a044a26263c`, branch `main`.
- 원본 최초 `git status --short --branch`: `## main...origin/main`, 변경 항목 없음. remote fetch는 하지 않았으므로 서버와 최신 동기화 여부는 확인하지 않았다.
- 대상 최초 상태: `.DS_Store`, `docs/FinCompass_Agent_Project_Plan.pdf`만 존재. Git 저장소 아님. 사용자 파일을 삭제하거나 덮어쓰지 않았다.
- Master Prompt의 초기화 요구에 따라 기준 commit의 Git 이력을 `git clone --no-hardlinks`로 독립 복제했다. 원본과 Git object hardlink/alternates를 공유하지 않는다. 임시 clone의 로컬 원본 remote는 제거해 실수로 Loanops에 push하지 않게 했다. 원본 remote 설정은 변경하지 않았다. target에도 remote를 설정하지 않았다.
- 대상의 기존 PDF를 보존한 상태로 baseline 소스를 초기화하고, 새 FinCompass `AGENTS.md`와 implementation 문서 3개만 작성한다. 기존 README/UI/Java/SQL/package lockfile의 내용은 그대로이다. commit/push 없음.
- `.env`, 로컬 DB, `.gradle`, `build`, `frontend/node_modules`, `dist` 등 비추적 실행환경/민감 설정은 대상 프로젝트 초기화에 복사하지 않는다.

## 2. 실제 기존 스택

- Backend: Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.14.3, Spring dependency-management 1.1.7.
- Persistence: Spring Data JPA + PostgreSQL + Flyway. `spring.jpa.hibernate.ddl-auto=validate`, schema `loanops`.
- 조회 계층은 JPA entity serialization 대신 `NamedParameterJdbcTemplate`의 고정 SQL과 전용 record DTO를 사용한다. 새 ORM가 이미 있는 것은 아니다.
- HTTP: Spring RestClient/Jackson, XML DOM parser. Actuator health/info, springdoc 2.9.1.
- DB image: PostgreSQL `17.11-alpine`, 운영 개발 compose와 테스트 모두 이 버전.
- 테스트: Spring Boot Test/JUnit 5, Testcontainers 2.0.5, WireMock 3.13.2. 모두 현재 5개 통합 테스트 클래스에 위치한다.
- Frontend: React/TypeScript/Vite. package.json 선언은 React `^19.1.1`, TS `^5.8.3`, Vite `^7.1.5`; 실제 lockfile/설치본은 React 19.3.0, TypeScript 5.9.3, Vite 7.3.6, plugin-react 5.2.0. 직접 dependency 8개의 설치본과 lockfile 버전 일치 확인.
- 기존 코드 규모: production Java 81개, test Java 5개, Git 추적 파일 137개. CI workflow와 formatter/linter 설정은 추적 파일에서 발견되지 않았다.

## 3. Backend / Domain / Data Pipeline

### source

`source/client/SourceAdapter.java` 아래 FSS JSON, 서울시 XML adapter가 있다. `SourceIngestionService`가 adapter를 선택한다. 서울시 pagination은 `instanceof SeoulLoanRateAdapter` 분기로 구현되어 범용 개인 Provider 추상화는 아니다.

`SourceRunLifecycleService`는 `REQUIRES_NEW`로 RUNNING 시작, raw 저장, SUCCESS/FAILED 종료를 각각 보존한다. raw는 원문·SHA-256·fetch 시각과 source run 관계를 가진다. HTTP 200 외에 FSS `000`/서울 `INFO-000` source 성공코드도 검증한다. `SourceErrorClassifier`는 원문 예외/키/인증 URL 대신 정제된 메시지를 저장한다.

주의: FSS `.retrieve()`의 HTTP 오류는 body 보존 전에 예외가 발생한다. `SourceIngestionIntegrationTest.http500PersistsFailedRunWithoutRawSnapshot`은 이 동작을 현재 기준으로 검증한다. 따라서 '모든 HTTP 응답 body가 보존된다'는 일반화는 정확하지 않다. 이번에 바꾸지 않았다. 개인 Gateway는 오류 응답/토큰 응답의 저장 및 redaction 범위를 새 계약으로 정해야 한다.

### normalization

`SourceNormalizationService.normalize`는 SUCCESS source run만 처리하고 전체 invocation을 트랜잭션으로 감싼다. FSS는 단일 raw snapshot에서 `baseList/optionList`를 읽고 product-option 관계와 필드를 검증한다. 서울시는 여러 snapshot의 total/row 합계를 검증해 불완전 dataset을 거부한다. lookup-before-insert와 DB unique constraint로 같은 snapshot 재정규화 중복을 방지한다.

`InstitutionResolver`는 FSS의 source code/name과 정확한 canonical name, 서울시의 명시적 alias mapping을 사용한다. 알 수 없는 서울시 기관은 `UNMAPPED`로 보존한다. fuzzy matching 없음.

상품 도메인은 `LoanProduct`(공시·기관·상품명), `ProductTerm`(담보·상환·금리유형 및 min/max/avg), `LoanRateObservation`(서울 정책연계 대출 집계), `FinancialInstitution`, `InstitutionAlias`이다. 각 product/term/observation은 정확한 raw snapshot을 참조한다. `LoanProduct`는 개인 보유대출이 아니다.

### publication

`QualityValidationService`는 필수필드, 날짜 순서, 음수 금리/건수, 금리 순서를 검증하고 issue/quarantine을 별도 트랜잭션으로 먼저 보존한다. FSS의 average는 min/max 범위 내일 필요가 없고, 서울 평균은 해당 범위에 있어야 한다. `QualityPolicy.CURRENT_VERSION=2026-09-v2`.

`PublicationService`가 기존 final 결과를 재사용하거나 `PublicationTransactionService`를 통해 run 전체를 승격/거부한다. invalid run은 모든 serving 변경을 차단하고, pointer 변경과 DataChange는 원자적으로 commit한다. business field만 비교하므로 provenance-only 새 run은 NO_CHANGE이고 pointer를 갱신하지 않는다. REMOVED를 추론하지 않는다.

정책·불변 레코드의 JPA 컬럼은 주로 `updatable=false`로 보호되지만, 이를 모든 DB 클라이언트에 대한 암호학적 불변성으로 주장하지 않는다. 동시 실행/역순 publication의 전체 안전성을 이번 47개 테스트로 입증한 것도 아니다.

## 4. DB와 migration

기존 migration 7개만 있다. 이번에 수정하거나 추가하지 않았다.

- V1 `initialize_database`: loanops schema.
- V2 `create_source_ingestion_tables`: data_sources, source_runs, raw_snapshots; 상태/오류/해시 제약과 FSS/서울 source seed.
- V3 `add_upstream_error_type`: HTTP 성공 안의 upstream failure 분류.
- V4 `allow_paginated_raw_snapshots`: run당 raw 1개 unique 제거, 복수 snapshot index.
- V5 `create_normalized_financial_models`: financial_institutions, institution_aliases, loan_products, product_terms, loan_rate_observations; 명시적 기관 alias seed.
- V6 `create_quality_and_serving_models`: data_quality_issues, quarantine_records, publication_runs, serving_loan_products, serving_product_terms, serving_loan_rate_observations, data_changes.
- V7 `add_quality_policy_version`: 과거 publication v1 보존, 이후 NOT NULL quality version.

합계 application table 15개(별도 Flyway 관리 테이블 제외). IDENTITY bigint, FK, unique/check, numeric(10,4) rate와 timestamp 기반. 개인 금융/동의/token/재무 metric/scenario/matching/agent 테이블은 없다. 기존 migration을 편집해 역사적 schema를 바꾸지 말고 승인된 후속 단계에 새 migration을 추가해야 한다.

상품/개인 모델 분리는 기존 JPA/Flyway를 공유하면서 테이블/서비스/접근 경계로 구현할 수 있다. 기존 product quality SQL CHECK와 source enum은 상품 특화이므로 개인 영역에 무리하게 확장하면 회귀 위험이 있다.

## 5. 현재 API / Frontend

현재 업무 API는 다음 10개 GET뿐이다.

- `/api/v1/overview`
- `/api/v1/source-runs`, `/api/v1/source-runs/{id}`
- `/api/v1/publications`, `/api/v1/publications/{id}`
- `/api/v1/quality-issues`
- `/api/v1/data-changes`
- `/api/v1/serving/loan-products`, `/api/v1/serving/loan-products/{id}`
- `/api/v1/serving/loan-rate-observations`

서비스: `api/OperationalReadService.java`. DTO: `api/dto/ApiDtos.java`. 오류: `ReadApiExceptionHandler`의 `timestamp/status/error/message/path`, 400/404. 새 JSON body validation/오류 유형은 이 envelope에 맞추되 후속 구현 필요.

목록은 결정적 정렬, 0-based page, 최대 size 100. 상품 조회는 normalized history가 아닌 serving pointer를 따른다. raw 응답은 hash/시각/크기 등 metadata만 공개한다. CORS는 `/api/**`의 GET, 기본 origin `http://localhost:5173`만 허용한다.

`frontend/src/App.tsx`는 useState로 운영 현황, 수집 이력, 데이터 품질, 제공 데이터 4개 view를 전환한다. React router/state library/chart library 없음. `api.ts`의 GET fetch/queryString, `types.ts`의 DTO, 공용 MetricCard/StatusBadge/UiState/Pagination을 재사용할 수 있다. Vite `/api` proxy는 localhost:8080. consumer UI나 금융 계산은 없다.

package.json scripts는 dev/build/typecheck/preview만 있다. frontend test runner/lint script가 없으며 타입 검사/빌드를 자동 UI 테스트로 보고하지 않는다.

## 6. 회귀 테스트 실행 방식

원본 디렉터리에서 Gradle/npm을 실행하지 않았다. 기존 테스트가 `build`, `.gradle`, `dist`, tsbuildinfo 등을 쓰므로 다음 격리 작업 디렉터리를 사용했다.

`/Users/jaemin/Documents/Codex/2026-09-21/x20/work/baseline/Loanops`

1. 원본 tracked 파일 137개의 SHA-256 manifest를 먼저 저장.
2. `git archive HEAD`로 정확한 commit 소스를 추출. 사용자 `.env`와 DB는 사용하지 않음.
3. 기존 Gradle caches/wrapper를 별도 `work/baseline/gradle-home`에 복사하고 `GRADLE_USER_HOME`으로 지정. 기존 frontend node_modules를 복사해 이미 설치된 패키지를 사용. 새 dependency 설치/업그레이드 없음.
4. 최초 Docker 조회는 sandbox 접근 제약, 권한 있는 재확인에서는 Docker Desktop 수동 pause를 확인. 사용자에게 해제를 요청했고 'Docker 실행했어' 응답 뒤 전체 suite를 실행. 이 사전 확인은 테스트 실패로 집계하지 않음.
5. Testcontainers가 임시 PostgreSQL을 생성하고 WireMock이 외부 API를 대체. 기존 compose DB/volume을 시작·정지·삭제하거나 migration하지 않음.

실행 명령(격리 backend 디렉터리에서):

```sh
JAVA_HOME='/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home' \
GRADLE_USER_HOME='/Users/jaemin/Documents/Codex/2026-09-21/x20/work/baseline/gradle-home' \
./gradlew test --rerun-tasks --no-daemon --offline
```

frontend 복사본에서:

```sh
npm run typecheck
npm run build
```

실행 환경: Temurin Java 21.0.12.1, Node v24.21.0, npm 11.19.0. Gradle 전체 명령 52초, 5 task 실제 실행. 테스트 종료 로그 2026-09-21 16:26:37 +09:00. 과거 `build/test-results`를 결과로 재사용하지 않았다.

## 7. 실제 결과

백엔드 **47 tests / 0 failures / 0 errors / 0 skipped**, Gradle exit 0, BUILD SUCCESSFUL.

- `LoanOpsApplicationIntegrationTest`: 3건 통과. PostgreSQL 연결/기동, Actuator, OpenAPI/Swagger.
- `SourceIngestionIntegrationTest`: 9건 통과. raw/hash, HTTP200 upstream failure, HTTP500/timeout, 실패·RUNNING lifecycle, schema.
- `PhaseThreeIntegrationTest`: 7건 통과. 정규화 관계/provenance/idempotency, 서울 pagination/mapping, malformed 전체 rollback, 실패 run 차단.
- `PhaseFourIntegrationTest`: 18건 통과. 승격·품질정책·금리/날짜/건수 오류·격리, 마지막 정상 보호, 원자적 rollback, NO_CHANGE/ADDED/UPDATED, 반복 publication.
- `ReadApiIntegrationTest`: 10건 통과. 운영 API, 필터/pagination, metadata-only, current pointer, 서울 데이터 의미, 400/404, GET-only CORS.

frontend **typecheck exit 0**, **build exit 0**. Vite 7.3.6, 39 modules, production bundle 생성, Vite 출력 기준 1.64초. frontend unit/component/E2E 테스트는 존재하지 않아 실행하지 않았다.

근거 원본은 작업 폴더 `work/baseline/backend-test.log`, `frontend-typecheck.log`, `frontend-build.log`, `test-summary.json`, `Loanops/build/test-results/test/TEST-*.xml`이다. 검토용 사본은 이 분석의 outputs/regression에도 제공한다.

## 8. Baseline이 보장하지 않는 것

- 실 FSS/서울/Open Banking/API 계정이나 기존 운영 DB 내용·최신성은 검증하지 않았다. 원본 `.env` 값을 읽거나 외부 API를 인증 호출하지 않았다.
- 테스트 통과가 새로운 개인 금융, 계산, matching, agent 요구사항의 충족을 의미하지 않는다.
- 현재 FSS 한 페이지 수집을 전체 시장 상품 완전성으로 볼 수 없다.
- 새 기능의 round-off, 규제 DSR/LTV/세금, 날짜·소득·분모 정책, 금리 단위는 아직 미정이다.
- raw/token privacy, multi-user isolation, 인증, 동시 실행, 성능/보안에 대한 포괄적 보증이 아니다.
- 브라우저 화면 수동/E2E 검증은 이번 regression에 포함하지 않았다.

## 9. 변경 검증 기준

Loanops 원본은 끝까지 read-only. 감사 전후 tracked 137개 SHA-256과 `git status`를 비교한다. 대상 초기화 후에는 baseline 대비 `AGENTS.md` 외 tracked 파일 136개의 내용 동일성을 확인한다. 기대 diff는 `AGENTS.md` 변경 + 기존 사용자 PDF/새 implementation 문서의 untracked 상태뿐이다. dependency/Java/frontend/migration diff가 있으면 이번 범위 위반으로 처리한다.
