# Phase 3 실제 검증 기록

실행일: 2026-09-21 (Asia/Seoul). 대상: FinCompass Agent / phase/03-public-mydata. 기존 Java 21/Gradle/PostgreSQL 17.11-alpine/Testcontainers/WireMock/npm 환경 사용. 새 dependency 없음.

## Git preflight / 통합

clean `phase/02-personal-finance` / `bc8a318` 확인 후 origin fetch, main 및 origin/main descendant 관계를 확인했다. Phase 2 branch push → main fast-forward → main push → phase/03-public-mydata 생성 순서로 실행했다. SSH origin은 `git@github.com:jmkim2021/FinCompass.git`. force push/기존 history rewrite 없음. Phase 3는 로컬 commit까지만 수행하고 원격 push하지 않는다.

## 실제 Backend 실행

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
GRADLE_USER_HOME='<local-audit-cache>' \
./gradlew test bootJar --offline --no-daemon
```

최종 **177 tests / 0 failures / 0 errors / 0 skipped**, BUILD SUCCESSFUL. JUnit XML을 합산했다. 기존 131개와 신규 46개이다. 최초 전체 173개도 통과했으며, 입력 정수 overflow/원천 시각 보존을 추가 보강한 최종 177개 결과를 완료 기준으로 삼았다. 기존 통과 기록을 현재 결과로 재사용하지 않았다.

중간 확인: V10 이후 기존 Open Banking canonical 36개 통과. 신규 MyData 초기 36개 통과. 이후 경계/동의 감사/rollback 테스트를 추가했다.

## 신규 46개

- `PublicMyDataIntegrationTest`: 44개. 실제 PostgreSQL에서 Main/Self-employed/Missing persona, generic-only FK, 두 provider wiring, 소득/재직 분리, sourceType/mode/consent/run/raw provenance, 같은 fixture idempotency, 금액 정밀도/0/NULL, provider scope 격리, missing/expired/revoked 동의, cross-user/connection 차단, 수집 후 동의 만료·철회 audit, stale asOf/귀속연도 적합성/설정 변경/read-time aging, 충돌·새 valid 교체·오래된 후보 차단, malformed/unsupported/future/overflow 입력, unknown sensitive field omission, 문자열/로그 비노출, 명시적 같은 demo user 연결과 다른 사용자 비병합, 동시 replay, canonical rollback/source 보존, 서로 다른 asOf/collectedAt/normalizedAt.
- `ProviderGeneralizationMigrationTest`: 1개. PostgreSQL V9를 먼저 적용한 뒤 legacy connection/run/raw, financial user/consent/account/provenance/validation/current를 실제로 채운다. V10만 추가 적용하여 raw/account 내용, V1–V9 checksum, current pointer와 기존 UUID를 보존하고 공통 참조의 올바른 backfill, Flyway.validate 및 재실행 0건을 확인했다.
- `PublicMyDataStandaloneTest`: 1개. Open Banking module/bean/credential 없이 별도 개인 저장 key로 기동·Mock 수집·정규화하는 경로를 검증했다. fixture freshness에 의존하지 않는 wiring 검증이다.

기존 테스트의 의미는 유지했다. V8→V9 migration 테스트는 target=9를 명시해 원래 구간을 계속 검사한다. clean DB 테스트의 최종 버전 기대값만 9→10으로 갱신했다. 기존 테스트를 삭제하거나 skip하지 않았다. 나머지 기존 테스트의 assertions는 변경하지 않았다.

## Frontend

```sh
cd frontend
npm run typecheck
npm run build
```

둘 다 exit 0. Vite production build 39 modules. frontend source/dependency 변경 없음. 기존 frontend에는 unit/E2E runner가 없어 타입 검사/빌드를 E2E PASS라고 표현하지 않는다.

## 실제 JAR 통합 replay

임시 PostgreSQL container에서 최종 bootJar를 `manual-public-mydata`, web=none으로 실행했다. DB password/암호화 key는 메모리에서 무작위 생성했으며 실제 API credential은 사용하지 않았다. 종료 코드 0. 검증 종료 후 해당 임시 컨테이너만 삭제했다.

DB 직접 조회 및 runner 결과:

- financial_users=1, provider_connections=2: 명시적으로 같은 demo owner/user 관계 확인.
- Open Banking: BALANCE SUCCESS 1page/1건, TRANSACTIONS SUCCESS 2page/28건.
- canonical: accounts=1, balances=1, transactions=28.
- income_evidence=2: 소득증명 1 + 재직 1. is_verified=true는 소득 row 1개만.
- MyData generic-only provenance=2: OB raw/source FK 없이 저장됨.
- INCOME/EMPLOYMENT/BALANCE/TRANSACTIONS 모두 VALIDATED_COMPLETE 및 promoted=true.
- loan/card/investment 5개 domain table 합계=0. Open Banking fixture 변경 없음.
- runner 출력: income=1, employment=1, Open Banking replay transactions=28.

66,000,000 KRW/2025/EMPLOYED와 48,000,000 KRW/SELF_EMPLOYED, incomplete persona, 반복 identity/hash는 통합 테스트에서 직접 검증했다. JAR 출력에는 금융 금액이나 evidence/연결 식별자를 넣지 않았다. 시간이 지나 fixture가 stale이 되는 것은 정상 정책이며 fresh로 가장하지 않는다.

## Privacy / 원본 무결성 / diff

- tracked/unignored candidate tree의 private-key/GitHub/AWS/Slack token 고확도 패턴 검사: 발견 0.
- .env/application-local/key 파일이 candidate tree에 없음. OPEN_BANKING 및 PERSONAL_FINANCE key 예시 값은 empty.
- runtime fixture는 최소 필드 allowlist와 synthetic PMD-DEMO ID만 사용. 실제 주민번호/정부 credential/가입자·고용주 식별자/문서 이미지 없음.
- unknown-field test의 금지된 필드명은 부정 테스트에만 있으며 실제 식별자 대신 SYNTHETIC-FORBIDDEN-MARKER를 사용했다. 해당 값은 저장·로그되지 않음을 검증했다.
- source/canonical payload 암호화 및 로그/toString redaction 통합 테스트 통과. 실제 JAR stdout에 실행 key/DB password/합성 계좌 식별자가 없음을 확인했다.
- `git diff --check`와 source/migration/test/docs diff review 완료. dependency/build/frontend/product code 및 V1–V9 migration 파일 변경 없음.
- ../Loanops tracked 137개 SHA-256는 Phase 0 manifest와 모두 일치. source git clean, HEAD `e9227c3` 유지.
- source-of-truth PDF SHA-256 유지: `90733790831ed8deaa5607d3b45653f12404aa9e93056153a30d19aeee9bbf2b`.

패턴 검사/검토는 모든 비밀을 탐지하는 DLP 보증이나 외부 보안 인증은 아니다.

## 미실시 / 후속 경계

- 실제 Public MyData: 의도적으로 미연동. 실제 전자서명/기관 인증/진위 검증/수취 endpoint 없음. 공개 개념·문서 유형 대응 Mock이지 실제 wire-schema conformance 인증이 아니다.
- 실제 KFTC: LIVE_TEST_NOT_VERIFIED 유지.
- Phase 4 Financial Twin/analytics 및 이후 기능은 미구현. source linkage와 검증된 current evidence만 제공한다.
- provider별 consent/current 확인, missing/stale/conflict, 관측 소득과 증빙 소득 분리, Golden fixture 추가 가정은 Phase 4에서 명시적으로 이어받아야 한다.
