# Phase 2 실제 검증 기록

실행일: 2026-09-21 (Asia/Seoul). 작업 저장소: FinCompass Agent. Java Temurin 21, 기존 Gradle 8.14.3, PostgreSQL 17.11-alpine Testcontainers/Docker, 기존 frontend 설치 의존성을 사용했다. ../Loanops에서는 명령의 write/build/test를 실행하지 않았다.

## Git 통합 및 범위

- Phase 1 `0a2fdb2`와 baseline `c1d729d`의 descendant 관계 및 clean 상태를 확인했다.
- `phase/01-open-banking` push → main fast-forward → main push → `phase/02-personal-finance` 생성 순서로 실행했다.
- SSH origin: `git@github.com:jmkim2021/FinCompass.git`. force push/기존 history rewrite 없음.
- Phase 2는 해당 branch에서만 작업하며 원격 push하지 않는다. 커밋 제목: `feat: add canonical personal finance data layer`.
- 기존 dependency/build 설정/frontend/product 코드/V1–V8 migration 변경 없음. Phase 3 시작하지 않음.

## Backend 전체

실행 명령(전용 Gradle cache 경로 설정 후):

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
GRADLE_USER_HOME='<local-audit-cache>' \
./gradlew test bootJar --offline --no-daemon
```

결과: **131 tests / 0 failures / 0 errors / 0 skipped**, BUILD SUCCESSFUL. 실제 JUnit XML을 합산했다. 기존 Phase 0+1의 89개와 신규 Phase 2의 42개가 포함된다. 기존 테스트 파일은 수정하지 않았다. 실행 JAR도 빌드했다.

신규 테스트 구성:

- `PersonalFinanceIntegrationTest`: 36개. Spring wiring, clean V9 migration, replay 1/1/28, 동일 run idempotency, repeated replay identity/hash, NULL/BigDecimal/음수잔액, Seoul→UTC/time/direction, raw/run/type/mode provenance, source observation timestamp, explicit LIVE_TEST origin, incomplete pagination + 이전 정상 manifest 보존 + retry, initial incomplete 차단, stale audit/current 보존, 시간 경과 freshness, 설정 threshold/정책 보존, missing income/capability warning, 급여 설명 비추론, owner/connection/scope/동의 필수·만료·철회·갱신, 이전 run 역순 정규화 차단, overlap 증분/비연속 기간 차단, raw 변조 INVALID, DB write rollback/current 보존, concurrent idempotency, 암호화·로그/문자열 redaction, bounded query, microsecond grant idempotency, 빈 complete 거래 기간, 잔액 source failure 보존.
- `PersonalQualityRulesTest`: 5개. 명시적인 대출 상환 구성요소 mismatch, scale에 무관한 정확한 합계, 결측값 비추론, freshness 경계, 잘못된 설정 거절. 실제 대출 row/provider는 생성하지 않는다.
- `PersonalFinanceMigrationTest`: 1개. 별도 PostgreSQL에서 V8까지만 적용하고 source connection/run/raw/checkpoint를 채운 뒤 V9로 upgrade. 기존 raw 전체 row/계좌 ciphertext/checkpoint, V1–V8 checksum 보존, 신규 canonical 비어 있음, 재실행 시 migration 0건 확인.

초기 신규 테스트 실행에서는 잔액 사례 하나가 current=NULL로 실패했다(37/38). 해당 테스트에 source SUCCESS와 promotion assertion을 추가하고 로컬 HTTP 정상 응답 대기시간을 300ms→2s로 조정했다. timeout 시나리오는 mock 지연 3s로 계속 실패하도록 유지했다. 이후 신규 39/39와 최종 전체 131/131 실제 실행을 확인했다. 실패한 첫 실행을 PASS로 계산하지 않았다.

## Frontend

```sh
cd frontend
npm run typecheck
npm run build
```

둘 다 exit 0. Vite production build 39 modules. frontend 소스/dependency 변경 없음. 별도 frontend unit/E2E runner는 여전히 없으며 이 검증이 브라우저 E2E를 대신한다고 주장하지 않는다.

## 실제 JAR replay

임시 PostgreSQL 컨테이너 + 임의 생성한 메모리 전용 DB password/encryption key로 빌드 JAR를 실행했다. LIVE client/secret/redirect/base URL 없이 `manual-personal-finance`, web=none, OPEN_BANKING_MODE=DEMO_REPLAY, PERSONAL_FINANCE_ENABLED=true를 사용했다. 종료 코드 0; 임시 컨테이너 제거 완료. 기존 loanops-postgres-1은 건드리지 않았다.

Phase 1 run:

- BALANCE SUCCESS / page 1 / record 1 / complete=true
- TRANSACTIONS SUCCESS / page 2 / record 28 / complete=true
- CARDS, LOANS, LEASES UNSUPPORTED / complete=false

V9 DB 직접 조회:

- financial_accounts=1
- balance_snapshots=1
- financial_transactions=28
- loan/card/investment/income 관련 6개 table 합계=0
- BALANCE 및 TRANSACTIONS: VALIDATED_COMPLETE / promoted=true
- service current: transactions=28 / completeForLatestAttempt=true

잔액금액/NULL/time/provenance와 반복 결과는 fixed-Clock integration test에서 검증했다. fixture가 오래되면 실제 JAR의 잔액은 정상적으로 STALE로 판정되며 이번 실행은 24h threshold 안이었다. replay fixture는 PDF Financial Twin persona와 별개이다.

## Privacy, diff, source integrity

- tracked/unignored candidate tree를 대상으로 private-key/GitHub/AWS/Slack token 고확도 패턴 검사: 발견 0.
- credential 예시 empty 확인. .env/application-local/key 파일이 candidate tree에 없음. 기존 gitignore 정책 유지.
- canonical 금융 payload는 SecretBox AES-GCM/AAD 재사용. integration test에서 저장 ciphertext와 DTO toString/log의 민감정보 비노출 확인.
- 실제 JAR stdout에서 실행 key/DB password/합성 금융 식별자 비노출 확인.
- `git diff --check` 통과. 신규/변경 파일 및 migration/service/promotion/consent/provenance/NULL/crypto 경로 수동 review.
- 고확도 패턴 검사와 수동 검토는 모든 비밀정보 탐지를 보장하는 DLP 검증은 아니다.
- ../Loanops 원본 tracked 137개 SHA-256를 Phase 0 사전 manifest와 비교: 변경 0개. source git status clean, HEAD `e9227c3` 유지.
- PDF SHA-256 그대로: `90733790831ed8deaa5607d3b45653f12404aa9e93056153a30d19aeee9bbf2b`.

## 검증하지 않은 범위

실제 금융결제원 LIVE_TEST 호출은 **미실시 / LIVE_TEST_NOT_VERIFIED**. WireMock 검증과 구분한다. 개발자 앱/client/secret/등록 redirect/기관 prefix/테스트 계좌/권한·안정적 key 준비 후 실제 계약 확인이 필요하다.

Public MyData Mock, 실제 소득 evidence, Financial Twin, 계산/매칭/Agent/consumer UI, production 인증/retention/key rotation은 이번 범위가 아니다. 자세한 한계와 Phase 3 handoff는 `PERSONAL_FINANCE_MODEL.md`에 있다.
