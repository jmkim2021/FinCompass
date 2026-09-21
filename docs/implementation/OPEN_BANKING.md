# Phase 1 — Open Banking Integration

업데이트: 2026-09-21. 제품 요구사항 기준: `docs/FinCompass_Agent_Project_Plan.pdf` 28쪽 전체, 특히 p.6 아키텍처와 p.22 구현 순서. 실제 외부 호출 상태: **LIVE_TEST_NOT_VERIFIED**.

사용자가 개발자 앱/credential/redirect URI/테스트 계좌 미준비를 확인하고, 이번 완료 범위를 adapter + WireMock + DEMO_REPLAY로 명시적으로 승인했다. 실제 금융결제원 호출 성공을 주장하지 않는다. Phase 2는 미착수다.

## 구조와 기존 subsystem 경계

`com.loanops.personal.openbanking` 안에 다음 구성요소를 둔다.

- `OpenBankingProvider`: mode/capability 및 잔액·거래 원천 응답 계약.
- `LiveTestOpenBankingProvider` → `OpenBankingTokenService` → `OpenBankingClient`: inquiry 권한, token 유효기간/refresh, HTTP 호출.
- `ReplayOpenBankingProvider`: 저장된 합성 fixture를 원천처럼 응답한다. 별도 계산/정규화 모델을 만들지 않는다.
- `OpenBankingSyncService` → `OpenBankingStore.raw` → `OpenBankingMapper` → evidence/checkpoint: 두 mode가 공유하는 raw → 검증·mapping → provenance 경로.
- `SourceSyncRun`, `RawOpenBankingSnapshot`: 외부로 전달 가능한 metadata DTO. token/account/raw body는 포함하지 않는다.
- `SecretBox`: AES-256-GCM 암호화 및 HMAC 거래 관측값 identity.
- `ManualOpenBankingConfiguration`: `manual-open-banking` profile의 로컬 운영자 실행 진입점.

기존 상품 ingestion/normalization/quality/quarantine/publication/serving/API/frontend는 변경하지 않았다. 개인 데이터를 기존 FSS/서울 source enum에 넣지 않는다. Spring/Jackson/JDK HTTP·crypto, 기존 JDBC/JPA transaction manager/Flyway만 사용하며 **dependency 추가 없음**. 고정 SQL 방식은 기존 `OperationalReadService`의 JDBC 사용과 같은 스택이다. 새 ORM이나 migration 도구를 도입하지 않았다.

Phase 0의 connection HTTP API는 설계 제안이었다. 이번 구현은 해당 HTTP API 대신 로컬 수동 실행과 Java service interface를 제공한다. 기존 애플리케이션에는 개인용 로그인/인가 기반이 없어 무인증 personal-data endpoint를 열지 않는다. callback HTTP 서버/consumer UI는 구현하지 않았다. 테스트 개발 앱의 등록된 redirect로 받은 code/state를 로컬 운영자가 env로 교환한다. `ownerRef`는 로컬 가명 식별자이며 실제 다중 사용자 인증을 대체하지 않는다.

## 확인한 공식 계약

2026-09-21 공개 문서 확인:

- [금융결제원 OAuth](https://developers.kftc.or.kr/dev/openapi/open-banking/oauth): OAuth 2.0 authorization code, 계좌등록 화면, 24자리 핀테크이용번호. 은행 계좌번호를 직접 수집하지 않는다.
- [잔액조회](https://developers.kftc.or.kr/dev/openapi/open-banking/balance): `GET /v2.0/account/balance/fin_num`, inquiry scope, balance/available amount 및 응답 시각.
- [거래조회](https://developers.kftc.or.kr/dev/openapi/open-banking/transaction): `GET /v2.0/account/transaction_list/fin_num`, 최대 25건/page, `next_page_yn`, `befor_inquiry_trace_info`.

공개 문서에서 HTTP 경로와 필드 계약을 확인한 것과 실제 개발자 계정의 접근 권한은 구별한다. 상세 OAuth 문서/앱별 설정은 로그인 후 확인이 필요하다. authorization/token URI와 API base URL에는 실제 발급된 **developer/test 환경** 값을 설정하며, 기본 호스트를 추정해서 넣지 않았다. 카드/대출·리스 API가 메뉴에 존재하더라도 이 앱의 사용 가능성이 검증된 것은 아니다.

## 실행 mode와 환경변수

기본 mode는 `DEMO_REPLAY`. module 기본 enable은 false이므로 기존 앱은 새 credential 없이 기동된다. 사용할 때 `OPEN_BANKING_ENABLED=true`로 켠다. LIVE_TEST 필수 credential 검증은 module 활성화 + LIVE_TEST 선택 시에만 한다. 활성화된 mode 모두 개인정보 저장용 암호화 key는 필요하다. DEMO에 실제 금융결제원 credential은 필요 없다.

공통:

- `OPEN_BANKING_ENABLED`: 기본 false.
- `OPEN_BANKING_MODE`: 기본 DEMO_REPLAY; 다른 값 LIVE_TEST.
- `OPEN_BANKING_ENCRYPTION_KEY`: Base64 인코딩된 무작위 32바이트 key. 기본값 없음. DB 사용 기간에 동일 key를 안전하게 유지한다.
- `OPEN_BANKING_ENCRYPTION_KEY_ID`: 기본 local-v1, envelope의 key 식별자.
- `OPEN_BANKING_INITIAL_LOOKBACK_DAYS`: 기본 180, 1~366.
- `OPEN_BANKING_OVERLAP_DAYS`: 기본 3, 초기 기간 이하.
- `OPEN_BANKING_MAX_PAGES`: 기본 1000, 안전 상한 10000.
- `OPEN_BANKING_CONNECT_TIMEOUT`: 기본 2s.
- `OPEN_BANKING_READ_TIMEOUT`: 기본 5s, token/GET 모두 적용.
- `OPEN_BANKING_MAX_RESPONSE_BYTES`: 기본 1048576, 최대 10485760.
- 기존 `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`: FinCompass 전용 DB. 기존 Loanops DB/volume을 지정하지 않는다.

LIVE_TEST 추가 필수:

- `OPEN_BANKING_CLIENT_ID`, `OPEN_BANKING_CLIENT_SECRET`.
- `OPEN_BANKING_BASE_URL`: developer/test API base.
- `OPEN_BANKING_AUTHORIZATION_URI`, `OPEN_BANKING_TOKEN_URI`: 전체 authorization/token endpoint URI.
- `OPEN_BANKING_REDIRECT_URI`: 개발자 앱에 등록한 redirect.
- `OPEN_BANKING_BANK_TRAN_PREFIX`: 기관에서 부여한 bank_tran_id 앞 10자리. 이후 U + 무작위 영숫자 9자리로 요청마다 20자리 ID 생성.
- `OPEN_BANKING_TEST_FINTECH_USE_NUM`: create 명령에만 필요한 24자리 등록된 테스트 식별자. DB에는 암호화하여 저장.

`OPEN_BANKING_TEST_USER_SEQ_NO`는 이번 fin_num 기반 두 조회 계약에서 사용하지 않아 수집·저장하지 않는다. 계좌목록/등록확인 자동화는 아직 구현하지 않는다.

로컬 실행 제어값:

- `OPEN_BANKING_ACTION`: demo(기본), create, authorize, exchange, sync, incremental, status.
- `OPEN_BANKING_OWNER_REF`: 기본 local-operator. 이름/주민번호 대신 가명 alias를 사용.
- `OPEN_BANKING_CONNECTION_ID`: create 결과의 UUID.
- `OPEN_BANKING_CONSENT_UNTIL`: create에 필요한 ISO-8601 UTC 동의 만료시각.
- `OPEN_BANKING_AUTHORIZATION_CODE`, `OPEN_BANKING_OAUTH_STATE`: exchange에만 사용, 로그/DB raw에 저장 안 함. 사용 후 환경에서 제거.
- `OPEN_BANKING_FROM_DATE`, `OPEN_BANKING_TO_DATE`: YYYY-MM-DD. sync는 둘 다, incremental은 to만 사용.
- `OPEN_BANKING_RUN_ID`: status 조회용 UUID.

환경변수 주입을 우선한다. 대안은 git-ignored `src/main/resources/application-local.yml`에서 `fincompass.open-banking.*` / datasource Spring 설정을 제공하는 방식이다. 이 repository는 backend/ 하위가 아니라 루트에 Java 소스가 있다. `--spring.profiles.active=local,manual-open-banking`으로 명시적으로 선택한다. `.env`는 Spring이 자동으로 읽지 않으므로 사용 중인 안전한 환경 로더를 통해 주입해야 한다. 실제 `.env`/local profile/key 파일은 작성하거나 commit하지 않았다. `.env.example`의 Open Banking credential 값은 비어 있다.

## DEMO_REPLAY 실행

자기 FinCompass DB 환경과 안전하게 생성한 encryption key를 env에 준비한 뒤:

```sh
export OPEN_BANKING_ENABLED=true
export OPEN_BANKING_MODE=DEMO_REPLAY
export OPEN_BANKING_ACTION=demo
./gradlew bootJar
java -jar build/libs/loanops-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=manual-open-banking \
  --spring.main.web-application-type=none
```

`demo-v1`은 PDF 금융 예시와 무관한 **합성 ingestion fixture**다. 2026-09-01의 25건과 09-02의 3건, 잔액 1,500,000을 사용하며 scenario golden truth가 아니다. demo 명령은 명시적으로 새 연결을 만들고 balance/transactions 및 미지원 capability 상태를 출력한다. 동일 연결의 재조회/dedupe 확인에는 create 이후 connection UUID를 고정하고 sync를 반복한다.

fixture timestamps는 버전 고정되어 시간이 지나면 stale로 판정된다. mode를 숨기거나 현재 시각의 fresh 금융 데이터인 것처럼 바꾸지 않는다. raw body의 fixture_origin도 보존한다. 결과 hash/거래 identity는 동일 입력·key·rule에서 같고, run/raw UUID·조회시각·암호문 nonce는 실행마다 다르다.

## OAuth/token lifecycle

1. create로 가명 owner/동의 만료/핀테크이용번호와 mode를 등록.
2. authorize: 암호학적 난수 state(32바이트)를 발급하고 SHA-256 hash만 저장. TTL 10분, 연결 소유권/동의 검사.
3. 공식 사용자 인증 화면에서 inquiry 동의를 완료. redirect의 code/state를 exchange env에 제공.
4. exchange: state를 조건부 DELETE로 한 번만 소비한 다음 token POST. 실패해도 state를 재사용하지 않으며 authorize부터 다시 시작한다.
5. access token 남은 유효기간 30초 이하이면 refresh. refresh 없으면 TOKEN_EXPIRED, 저장된 token 없으면 TOKEN_REQUIRED.
6. 갱신 응답의 refresh/scope 생략 시 기존 값을 유지. 성공한 갱신만 암호화 token을 교체한다. 실패 시 TOKEN_REFRESH_FAILED와 이전 ciphertext를 유지하며, 만료 token으로 금융 API를 호출하지 않는다.

token 갱신은 연결 row의 `FOR UPDATE` lock을 사용하는 별도 transaction으로 직렬화한다. 동시 process의 refresh rotation 중복을 방지한다. 네트워크는 설정된 timeout 동안 해당 lock을 점유한다. token 응답과 HTTP header는 raw snapshot에 저장하지 않는다. authorization URL은 로컬 운영자에게만 출력하는 일회성 입력이며 공유 로그로 수집하지 않는다.

## Sync lifecycle, 완전성, freshness

- capability마다 독립 RUNNING row 생성. 연결+capability의 RUNNING은 partial unique index로 하나만 허용. 동시에 같은 capability를 호출하면 SYNC_ALREADY_RUNNING.
- 각 응답 raw와 page_count는 외부 호출/최종 mapping과 분리한 transaction으로 보존.
- page_count는 **수신해서 raw를 남긴 응답 수**, record_count는 **성공적으로 검증한 거래 row 수**다. HTTP 오류 응답도 raw page로 집계될 수 있다.
- 모든 page 완료 후 transaction evidence insert + checkpoint + SUCCESS를 하나의 transaction으로 commit. 중간 timeout/응답 오류면 evidence/checkpoint는 승격하지 않는다.
- FAILED / UNSUPPORTED도 별도 transaction으로 남는다. page 이후 실패는 PAGINATION_INCOMPLETE와 상세 failureCause(TIMEOUT 등). 처음부터 응답을 못 받으면 TIMEOUT/CONNECTION_ERROR 등과 complete=false.
- complete=true는 **그 요청의 capability와 기간**만 의미한다. 전체 개인 자산·거래 이력·Financial Twin 완전성을 뜻하지 않는다.
- balance observation 시각은 api_tran_dtm(Asia/Seoul)을 UTC로 변환. 없으면 receivedAt을 사용한다. last_tran_date를 잔액 기준시각으로 사용하지 않는다. 원문을 통해 fallback 여부를 판별할 수 있다.
- balance 음수는 허용한다. 미래 시각 5분 초과/금액 파싱 오류는 거절. `OpenBankingMapper.stale(balance, now, maximumAge)`는 명시적 freshness 기준을 받으며 Phase 1에서 전역 SLA를 추정하지 않는다.
- 동의는 호출 시작, 거래 각 페이지 시작 및 승격 전에 확인한다. 만료/소유권 불일치/다른 mode의 연결은 조회를 허용하지 않는다.

자동 retry는 0회다. OAuth code/refresh 및 cursor 조회의 재시도 계약이 실환경에서 확인되지 않았으므로 자동 재전송하지 않는다. 일시적 실패 후 동일 기간을 명시적으로 다시 sync하면 overlap/dedupe와 마지막 정상 checkpoint 보존으로 안전하게 회복할 수 있다. LIVE 실패를 replay로 전환하지 않는다.

## Pagination / incremental / dedupe

조회 범위는 inclusive from/to, inquiry_type=A, inquiry_base=D, sort_order=A. 최대 한 번의 범위는 366일이며 미래 날짜는 거절한다. 각 page에서 count/res_list 일치, 0~25건, 날짜 범위/시간/입출금 구분/금액을 검증한다.

종료는 반드시 next_page_yn=N이다. 25건이라고 무조건 다음 페이지를 가정하지 않으며 짧은 페이지라도 Y면 계속한다. Y에는 비어 있지 않은 cursor와 1건 이상 응답이 필요하다. 같은 cursor 반복/설정 상한 도달/필드 오류는 incomplete다. 마지막 0건/N은 정상 완료할 수 있다.

incremental의 기준은 마지막 거래일이 아니라 **성공한 연속 조회 범위의 종료일**이다. 거래가 없는 날도 조회 완료 범위로 인정한다. 처음에는 to−(lookback−1), 이후에는 max(coveredFrom, coveredThrough−overlap)부터 조회한다. 연결된 범위만 합쳐 checkpoint를 확장하며 과거 재조회가 기준일을 후퇴시키거나 떨어진 backfill 사이의 미조회 구간을 덮지 않는다.

공개 거래 명세는 page/API 요청 ID와 거래 관측 필드를 제공하지만 은행 원장별 불변 개별 transaction ID는 제시하지 않는다. 따라서 요청의 api_tran_id/bank_tran_id를 거래 ID로 오용하지 않는다. `ob-composite-v1`은 연결 ID로 scope를 분리하고 다음 순서의 JSON tuple을 HMAC-SHA256 한다:

`date, time, direction, tran_type, amount, after_balance, print_content, branch_name`

돈은 BigDecimal의 불필요한 trailing zero를 제거해 표현 차이를 통일한다. public 명세의 print_content를 사용한다(일부 공개 예제의 printed_content 표기와 구별). DB unique(connection_id, identity_hash)와 ON CONFLICT로 동일 관측값을 한 번만 적재하고 inserted/duplicate count를 기록한다. 모든 원문 page와 raw 안의 중복 row는 유지한다.

**한계:** 동일 초·동일 설명·동일 금액·동일 후잔액을 가진 실제 별개 거래는 이 index에서 충돌할 수 있다. 반대로 은행의 설명/후잔액 정정은 새 관측값이 된다. 이것은 canonical 원장이 아니라 ingestion evidence index다. Phase 2는 raw occurrence/정정/ambiguity 정책을 처리하고 검증 전 완전한 개인 거래원장이라고 주장하면 안 된다. 같은 테스트 계좌를 새 connection으로 만들면 connection별 identity가 달라진다.

## V8 persistence와 provenance

`V8__create_open_banking_foundation.sql`은 loanops schema에 6개 테이블만 추가한다:

1. open_banking_connections: 가명 owner/mode/동의/암호화 계좌 및 token.
2. open_banking_oauth_states: state hash/connection/expiry.
3. source_sync_runs: capability/기간/시각/status/counts/completeness/error.
4. raw_openbanking_snapshots: run/endpoint/요청·수신시각/http·result status/SHA-256/API version/page/redacted/암호문.
5. open_banking_transaction_evidence: identity rule/첫 raw 참조/거래일/암호화 관측값.
6. open_banking_sync_checkpoints: capability별 마지막 정상 run/연속 범위/syncedAt/암호화 balance.

기존 V1~V7/15개 상품 테이블은 수정하지 않았다. V8 제거를 검토해야 한다면 신기능 중지·증거 백업 후 checkpoint→evidence→raw→runs→states→connections 순서의 신규 테이블 제거가 개념적 역순이다. 자동 down migration이나 실제 rollback은 수행하지 않았다. 운영 DB에 적용하지 않았으며 임시 PostgreSQL에서 신규 설치와 기존 migration 연속 적용을 검증했다.

## 보안/보존 경계

- token, account, raw body, mapped 금융 관측값을 AES-256-GCM으로 암호화. row ID와 용도를 AAD로 결합하고 random nonce를 사용한다.
- token/client secret/encryption key는 일반 JSON DTO 직렬화와 toString에서 제외. 응답/exception은 고정 오류 category만 남겨 upstream URL/body/credential이 예외 cause에 들어가지 않게 한다.
- raw에 token/secret/authorization/header/password/API-key/code/state 필드 및 알려진 token/client-secret 문자열이 반사되면 제거한다. 이스케이프된 JSON 문자열·배열·field name도 처리한다. redacted=true로 표시. 정제된 응답으로 mapping하여 관측값의 설명 필드에도 반사된 secret이 들어가지 않게 한다.
- 파싱 불가능한 raw는 비밀값 제거를 증명할 수 없어 원문 payload 대신 omission marker를 암호화 저장한다. response_hash는 수신 UTF-8 body 기준이며 redacted payload hash와 다를 수 있다. 이 경우 정확한 body replay가 불가능하다는 사실을 숨기지 않는다.
- HTTPS만 허용하며 WireMock/등록된 로컬 redirect용 localhost/127.0.0.1 HTTP는 허용한다. HTTP redirect는 따라가지 않는다. 요청/응답 크기를 제한하고 HTTP trace/header logging은 운영 설정에서 켜지 않는다.
- 개인 raw/evidence read HTTP endpoint 없음. public read method는 owner 확인 후 metadata/현재 balance만 반환한다. SQL credential과 로컬 OS 계정이 운영자 접근 경계다.
- 동의 만료는 추가 수집과 balance 읽기를 막는다. 법적 동의 증빙/철회 UI, 보존기간별 자동 삭제, 다중 사용자 인가, 외부 KMS/key rotation은 구현 범위 밖이다. 현재 test DB는 운영자가 짧은 검증 종료 후 폐기해야 한다. 실제 개인정보 장기 보관 정책을 확정하기 전 운영 수집용으로 배포하지 않는다.
- encryption key 변경은 기존 암호문 복호화를 깨뜨린다. identity도 동일 key에서 유도하므로 무계획 key 변경은 중복 의미를 바꾼다. 기존 keyring/재암호화/dedupe 재색인 계획 없이 변경하지 않는다.

## 테스트와 실제 결과

2026-09-21 실제 실행 결과는 `PHASE1_VALIDATION.md`에 기록했다.

- WireMock: authorization state/code exchange, refresh/expiry/동시 갱신, 성공·실패·redaction, balance mapping, transaction 경계/페이지/timeout/중복/incremental/overlap/실패 격리.
- Testcontainers PostgreSQL: 실제 Flyway V1~V8와 evidence/checkpoint transaction, 이전 정상 데이터 보존, owner/mode/consent, RUNNING 중복 방지.
- DEMO_REPLAY: 기본 Spring wiring 및 raw hash/동일 관측값 재현, 네트워크 호출 0. 빌드 JAR 수동 실행도 별도 임시 DB에서 확인.
- 기본 앱과 기존 47개 regression을 모두 실행. frontend typecheck/build 실행, frontend unit/E2E runner는 여전히 없음.
- 실제 금융결제원 API 호출: **미실시 / LIVE_TEST_NOT_VERIFIED**. credential을 제공받지 않았고 새 개발자 앱을 대신 생성하지 않았다.

## LIVE_TEST 준비 절차와 Phase 2 전달 사항

실제 검증에 필요한 사용자 준비: 개발자 앱 승인/credential, inquiry 권한, 등록 redirect, 최신 상세 OAuth 계약, 테스트 사용자 및 공식 화면에서 등록한 fintech_use_num, 기관 prefix, developer/test API·authorization·token URI, FinCompass 전용 DB와 안정적으로 관리하는 암호화 key.

준비되면 mode/env만 바꿔 create→authorize→exchange→sync→incremental을 실행한다. 현재 확인한 contract가 유지되는 한 코드 변경은 필요 없다. 민감값 대신 run UUID/mode/capability/status/count/hash만 검증 증거로 기록한다. 앱별 추가 계약 차이는 실제 검증에서 확인할 잔여 위험이다.

Phase 2가 받을 interface: SourceSyncRun.complete/error/count/범위, RawOpenBankingSnapshot metadata, successful checkpoint, Balance.observedAt/sourceType, 관측 거래와 raw provenance, mode/capability. Phase 2는 incomplete source를 complete canonical dataset으로 승격하지 말아야 한다.

잔여 debt: 실제 callback/user authorization, 계좌 등록확인/목록, 보존·철회/키 회전, crash로 남은 RUNNING 수동 복구 절차, 정정·동일 거래 ambiguity, 기관별 freshness/DQ. crash RUNNING은 자동 steal하지 않으며 운영자가 process 종료와 마지막 evidence를 확인한 뒤 별도 복구해야 한다. Phase 1에서는 Financial Twin/시뮬레이션/Matching/Opportunity/Agent/consumer UI를 구현하지 않았다.
