# Phase 1 검증 기록

실행일: 2026-09-21 (Asia/Seoul). 대상: FinCompass Agent `phase/01-open-banking`.

## Git preflight 및 baseline

- 사용자가 제공한 SSH `git@github.com:jmkim2021/FinCompass.git`에서 ls-remote 성공, refs 없는 empty repository 확인.
- 로컬 main은 LoanOps `e9227c3` history를 보존. 사전 diff는 앞서 작성한 Phase 0 문서/AGENTS/PDF뿐이고 사용자 코드 변경은 없었음.
- Phase 0 baseline `c1d729d47c75c3e2468f52abbbfbfa66d8aeea0c` 생성, main 최초 push 성공, remote main hash 일치 확인.
- `phase/01-open-banking`에서만 구현. 기존 history rewrite/reset/force push 없음. Phase 1 branch push는 아직 하지 않음.

## Backend 실제 실행

Java: Temurin 21. Gradle wrapper: 8.14.3. 기존 dependency cache로 offline 실행. PostgreSQL 17.11-alpine Testcontainers와 WireMock 사용.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
GRADLE_USER_HOME='<isolated Gradle cache>' \
./gradlew test bootJar --rerun-tasks --offline --no-daemon
```

최종 결과: **89 tests, failures 0, errors 0, skipped 0. BUILD SUCCESSFUL in 1m 1s.** 기존 47개 + 신규 42개. 모든 클래스의 XML을 합산했으며 filtered test 실행 결과를 전체 regression이라고 표시하지 않았다.

- `com.loanops.LoanOpsApplicationIntegrationTest`: 3 tests, 실패/오류/skip 0.
- `com.loanops.PhaseFourIntegrationTest`: 18 tests, 실패/오류/skip 0.
- `com.loanops.PhaseThreeIntegrationTest`: 7 tests, 실패/오류/skip 0.
- `com.loanops.ReadApiIntegrationTest`: 10 tests, 실패/오류/skip 0.
- `com.loanops.SourceIngestionIntegrationTest`: 9 tests, 실패/오류/skip 0.
- `com.loanops.personal.openbanking.OpenBankingIntegrationTest`: 32 tests, 실패/오류/skip 0.
- `com.loanops.personal.openbanking.OpenBankingReplayApplicationTest`: 1 tests, 실패/오류/skip 0.
- `com.loanops.personal.openbanking.OpenBankingUnitTest`: 9 tests, 실패/오류/skip 0.

새 테스트는 token acquisition/state 만료·재사용/owner, refresh 성공·실패·동시 갱신·생략 필드, 만료·미보유 token, balance 정밀 mapping·stale·invalid, 1/25/복수/빈 최종 page, 중복·overlap·incremental·불연속 backfill, page timeout/반복 cursor/상한/invalid row, partial capability, 이전 정상값 보존, DB 실패 시 evidence 전체 rollback, raw/DTO/log/반사된 secret 제거, 응답 크기/redirect 경계, DEMO 재현성과 Spring 기본 구성을 검증한다. 금리 percent/fraction 단위도 테스트했다.

실행 중 첫 컴파일에서는 java.sql.Connection과 내부 Connection 이름 충돌을 발견하고 import를 수정했다. 이후 컴파일·테스트는 통과했다. 최초 신규 테스트 실행 통과 뒤 secret 정제 경계를 보강했고, 최종 전체 suite를 다시 실행했다. 최종 suite에 실패/skip은 없다.

## Frontend 실제 실행

FinCompass frontend 디렉터리에서 기존 lockfile과 같은 이미 설치된 dependency cache를 사용했다. dependency 추가/업그레이드 없음.

```sh
npm run typecheck
npm run build
```

두 명령 통과. Vite 7.3.6, 39 modules, production build 1.25s. 출력 JS 246.07 kB (gzip 75.08 kB), CSS 11.36 kB (gzip 3.24 kB). 기존 Phase 0 산출물과 같은 파일 hash 이름. 자동 unit/E2E runner는 기존 repository에 없어 실행했다고 주장하지 않는다.

## DEMO_REPLAY JAR smoke

사용자 DB/Loanops volume과 무관한 새 임시 PostgreSQL 컨테이너를 localhost 임의 포트로 실행하고, 임시 암호화 key와 DB credential은 process 환경에만 주입했다. 실제 LIVE_TEST credential은 비워 두었다.

```sh
java -jar build/libs/loanops-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=manual-open-banking \
  --spring.main.web-application-type=none
```

`OPEN_BANKING_ENABLED=true`, `OPEN_BANKING_MODE=DEMO_REPLAY`, `OPEN_BANKING_ACTION=demo` 사용. 종료 코드 0. DB에서 다음 최종 metadata를 직접 조회했다:

- BALANCE: SUCCESS, page_count=1, record_count=1, complete=true.
- TRANSACTIONS: SUCCESS, page_count=2, record_count=28, complete=true.
- CARDS / LOANS / LEASES: UNSUPPORTED, page_count=0, record_count=0, complete=false.

임시 컨테이너만 제거했고 사용자 컨테이너/DB를 정리하지 않았다. 외부 금융 API를 호출하지 않는 replay provider로 실행했다.

## Diff, 원본 보존, secret 검사

- `git diff --check` 통과.
- 기존 Loanops tracked 파일 137개의 SHA-256을 Phase 0 manifest와 다시 비교: 변경 0. 원본 git status clean 확인.
- FinCompass의 기존 backend product source, V1~V7, frontend source, build.gradle, package/lock dependency는 변경하지 않음.
- `.env`, application-local.yml/yaml, `.local`, secrets/key 파일 git-ignore 확인.
- 신규 Open Banking 예시 credential은 모두 빈 값. 실제 로컬 secret 파일은 만들지 않음.
- Git candidate tree의 private key/GitHub token/AWS key/Slack token 고신뢰 패턴 검사: 검출 0. credential-bearing 파일 경로 없음. baseline push 전 기존 history blob도 고신뢰 패턴 검사함.
- 이는 전용 DLP 도구의 모든 패턴을 보장하는 결과는 아니다. runtime secret 미노출은 WireMock/captured log/암호문 복호화 검사로 별도 검증했다.

## 실제 LIVE_TEST 검증 상태

**LIVE_TEST_NOT_VERIFIED — 실제 금융결제원 API 호출 미실시.**

실제 개발자 앱/client credential/등록 redirect/테스트 계좌가 없다는 사용자 확인에 따라 이번 Phase 1의 blocker로 두지 않았다. 공식 공개 계약을 확인한 adapter 구현과 WireMock 검증은 완료했다. 실제 provider가 발급한 토큰이나 실제 금융 응답 증거는 없다.

추가 준비사항과 실행 순서는 `OPEN_BANKING.md` 참조. Phase 2/실제 운영 배포는 시작하지 않았다.

## 증거 파일

작업 session의 `work/phase1/`에 backend-full-test.log, frontend-typecheck.log, frontend-build.log, demo-smoke.log, demo-smoke-summary.json, test-summary.json, secret-integrity-audit.json을 보존했다. 사용자 전달용 사본은 최종 보고서의 outputs/phase1/evidence에 둔다. repository에는 임시 실행 로그·암호화 key·실제 금융 데이터를 commit하지 않는다.
