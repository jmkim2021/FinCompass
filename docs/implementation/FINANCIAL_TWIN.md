# Financial Twin — Phase 4 구현 계약

## 경계와 재사용

구현 패키지는 `com.loanops.fincompass.twin`이다. 기존 `personal.canonical.PersonalFinanceService`에서 소유자·동의 범위와 품질 상태를 확인한 잔액, 거래, 소득 evidence를 읽는다. 상품 source/normalization/publication/serving은 수정하지 않는다. 원천 조회, 계산, 저장, HTTP 응답 책임을 `FinancialTwinService`, `TwinCalculator`, `FinancialTwinStore`, `TwinController`로 나누었다.

Golden 원천의 구체적인 값·출처·가정은 `GOLDEN_HOME_BUYER.md`를 따른다. 이 문서는 일반 계산과 보존 정책을 설명한다. 테스트 PASS는 실제 실행 후 `STATUS.md`의 결과로 확인한다.

## 계산 입력과 원천 참조

`TwinModels.Input`은 financialUserId, 평가 기준 asOf, 고정 sourceRefs, supplemental facts, 잔액, 검증 연소득, 분류된 거래, 관측 완료 개월 수, capability별 사용 가능 여부와 warnings를 담는다. record의 collection은 방어적 복사하여 호출자가 나중에 목록을 변경할 수 없게 한다.

`SourceRef`에는 다음 항목을 보존한다.

- canonical record ID와 provenance ID.
- 정확한 validation run ID.
- sourceType와 sourceMode.
- 해당 원천의 asOf와 frozen record content hash.
- `LIQUID_ASSETS`, `CASHFLOW`, `VERIFIED_ANNUAL_INCOME` 또는 fact kind 등 계산 목적.

원천 raw/provider run·동의의 세부 연결은 immutable canonical provenance를 통해 추적한다. snapshot에는 보안 원문이나 OAuth 토큰을 노출하지 않는다. 보충 입력은 별도 `Fact` ID, kind, value, sourceType/mode, asOf, 통화 문맥, evidence ID를 갖고 `USER_INPUT`의 `DEMO_REPLAY` 또는 `MANUAL`만 허용한다.

거래 읽기는 canonical API의 100건 페이지 제한을 따라 수행한다. 읽는 동안 authoritative validation ID가 바뀌면 혼합 snapshot을 만들지 않고 재시도를 요구한다. 100,000건을 초과하는 거래 집계는 bounded request로 거절한다. 전체 월의 첫날부터 마지막 날까지 연속 검증된 구간만 월평균 계산에 사용하고, 부분 월을 완전한 한 달로 확대하지 않는다.

## 품질과 분류

잔액과 소득에는 `VALIDATED_COMPLETE`, latest-attempt complete, `FRESH`가 모두 필요하다. 거래에는 최신 완전 검증 구간이 필요하다. 원천 실패·불완전·stale 값을 정상 현재값으로 조용히 바꾸지 않는다. 사용할 수 없는 capability는 해당 metric을 unavailable로 만든다.

거래 분류는 `INCOME`, `LIVING_EXPENSE`, `DEBT_SERVICE`, `TRANSFER`, `UNCLASSIFIED`이다. 명시적 classification이 없으면 `UNCLASSIFIED`로 처리한다. 은행 설명문에서 merchant나 채무를 LLM으로 추정하지 않는다. 소득이 출금이거나 생활비/상환이 입금인 방향 충돌은 경고와 사용 불가로 처리한다.

미분류 거래가 있으면 관측 소득, 생활비 기반 free cashflow는 `INSUFFICIENT_DATA`이다. 관측 유출 총계는 알 수 있는 출금 금액을 포함하되 `DEGRADED`와 `UNCLASSIFIED_DEBITS_INCLUDED`를 붙인다. 내부 이체는 소득·지출에서 제외한다.

Open Banking 응답에 통화 필드가 없다는 사실을 유지한다. `ACCOUNT_CURRENCY_KRW=1`이라는 명시적 사용자 입력이 없으면 `UNCONFIRMED_ACCOUNT_CURRENCY`와 함께 잔액·거래 계산을 차단한다. 마이너스 잔액을 음수 유동자산으로 바로 합산하지 않고 부채 분류가 필요하다는 경고를 낸다.

## 지표 11개와 산식

모든 숫자는 backend `BigDecimal`로 계산한다. LLM과 frontend는 계산하지 않는다. 검증 연소득과 관측 월 입금은 서로 다른 입력이다.

1. `TOTAL_ASSETS`: 유동자산 + 명시적 기타 자산.
2. `TOTAL_LIABILITIES`: 명시적으로 입력한 기존 대출 잔액 합계.
3. `LIQUID_ASSETS`: 사용 가능한 원천 잔액.
4. `NET_WORTH`: 총자산 − 총부채.
5. `OBSERVED_MONTHLY_INFLOW`: 분류된 소득 입금 합계 ÷ 완전 관측 개월 수.
6. `OBSERVED_MONTHLY_OUTFLOW`: 이체를 제외한 출금 합계 ÷ 완전 관측 개월 수.
7. `MONTHLY_FREE_CASHFLOW`: 관측 월 소득 − 월 생활비 − 예정 월 부채상환액 − 별도 월 고정 의무.
8. `EMERGENCY_FUND_MONTHS`: 유동자산 ÷ 명시적 월 필수 지출.
9. `DEBT_SERVICE_BURDEN`: 예정 월 부채상환액 × 12 ÷ 검증 연소득.
10. `DEBT_TO_ASSET_RATIO`: 총부채 ÷ 총자산.
11. `LIQUID_ASSET_RATIO`: 유동자산 ÷ 총자산.

관측 부채상환은 관측 유출에 포함되지만 free cashflow에서 생활비와 함께 다시 차감하지 않는다. free cashflow는 예정 부채상환 입력을 한 번만 사용한다. 별도 고정 의무는 생활비와 겹치지 않는다는 명시적 입력 계약을 따른다. 필수 지출은 비상자금 분모이며 생활비 외의 추가 차감액이 아니다.

추가 자산이 주어지지 않으면 분석 자산 범위를 유동자산으로 제한하고 `ASSET_SCOPE_LIQUID_ONLY` limitation을 기록한다. 일반 사용자에게 자산 포괄성이 보장된다고 주장하지 않는다. Golden은 `OTHER_ASSET=0`을 명시한다.

돈은 최종 scale 2, 비율은 scale 8, 월평균 중간값은 scale 12, 반올림은 `HALF_EVEN`이다. 단위는 `KRW`, `KRW_PER_MONTH`, `MONTHS`, `FRACTION`으로 응답에 함께 기록한다. 부채상환 부담은 분석용 지표이며 법정/규제 DSR이나 대출 승인 판정이 아니다.

0 또는 알 수 없는 분모를 0으로 대체하지 않는다. 해당 값은 JSON null, availability `INSUFFICIENT_DATA`, `ZERO_DENOMINATOR` 또는 결측 경고이다. 부분적으로 알려진 값은 `DEGRADED`를 사용할 수 있다. 각 metric이 독립적으로 `AVAILABLE`, `INSUFFICIENT_DATA`, `DEGRADED`와 warnings를 갖는다.

## fingerprint와 역사 보존

`financial-twin-v1`이 현재 calculationRuleVersion이다. `TwinCalculator.fingerprint()`는 다음 고정 입력을 길이 접두 인코딩한 뒤 SHA-256으로 해시한다.

- 계산 규칙 버전, 사용자 ID, 평가 asOf.
- 원천 레코드·provenance·validation ID, sourceType/mode, 원천 asOf, 내용 hash, 용도.
- supplemental fact ID, kind, 값과 출처, asOf, 통화 문맥, evidence ID.
- transaction ID, 금액, 방향, 명시적 category.
- 잔액·검증 연소득, 완료 개월 수, 사용 가능 여부, 입력 경고.

목록은 정렬하고 decimal은 trailing zero를 제거하여 입력 목록 순서나 `1.0`/`1.00` 표기 차이만으로 새 계산을 만들지 않는다. 반대로 record/validation/내용/분류/가정/규칙이 달라지면 fingerprint가 달라진다. 과거 결과를 현재 규칙으로 제자리 재계산하지 않는다.

`FinancialTwinStore`는 소유자 행 lock과 `(financial_user_id, input_fingerprint)` unique constraint로 동일 frozen input 저장을 재사용한다. fact ID 재사용 시 다른 사용자이거나 내용이 달라지면 거절한다. 정확히 같은 입력이 같은 snapshot을 반환하는 것과, 새로운 입력으로 별도 snapshot을 만드는 것을 구분한다.

새 V11 migration은 다음 네 테이블을 추가한다. V1~V10은 변경하지 않는다.

- `financial_snapshot`: snapshot 식별자, 소유자, asOf/createdAt, 상태, 계산 규칙, fingerprint와 암호화된 input/result payload.
- `financial_snapshot_source_ref`: snapshot에 고정된 각 원천 참조와 용도.
- `financial_metric`: metric별 availability와 암호화된 값/경고.
- `user_entered_financial_fact`: 소유자별 고정 supplemental input.

네 테이블 모두 UPDATE/DELETE 거부 trigger를 두어 역사 변경을 막는다. 민감한 입력과 금융 결과는 기존 `PersonalFinanceCipher`의 암호화 및 context binding을 재사용한다. SQL 저장 메타데이터는 ID·시각·출처·hash 중심이며 토큰이나 원문 금융 데이터는 저장하지 않는다.

모든 지표가 AVAILABLE인 snapshot은 `COMPLETE`, 아니면 `INCOMPLETE`이다. latest 조회는 가장 최근의 COMPLETE를 우선하고, 정상 snapshot이 전혀 없을 때만 최근 INCOMPLETE를 반환한다. 따라서 이후 실패/불완전 snapshot이 기존 사용 가능한 Twin을 대체하지 않는다. 각 snapshot은 해당 평가 시점의 frozen 상태이며 향후 시간이 흘렀다는 이유로 과거 결과를 변조하지 않는다.

## HTTP API와 보안 범위

소비자 route는 `fincompass.demo.enabled=true`일 때만 등록된다.

- `GET /api/v1/financial-snapshots/latest`
- `GET /api/v1/financial-snapshots/{id}`
- `GET /api/v1/analytics/cashflow`
- `GET /api/v1/analytics/debt`

Phase 4의 세 summary 경로는 같은 frozen snapshot DTO를 반환하여 metric, input/sourceRefs, 기준일, rule version, fingerprint, warnings/limitations를 함께 확인할 수 있다. ID 조회도 고정 demo owner로 범위를 제한한다. 없는 snapshot은 기존 스타일의 오류 응답을 반환한다.

현재 HTTP API는 **로컬 DEMO 전용 소유자 `golden-homebuyer`**를 사용한다. production 사용자 인증은 아직 구현하지 않았다. 외부 multi-user 서비스의 인증·인가가 완료된 것으로 간주하지 않는다. demo를 끄면 소비자 route와 자동 bootstrap는 비활성화된다. 서비스/저장 계층의 owner-scoping은 계속 유지된다.

## 검증과 남은 제한

필요한 검증은 순수 BigDecimal 산식, 원천 결측/stale/부분 구간, 0 분모, 분류 오류·이중 차감 방지, fingerprint 결정성, 동일 입력 재사용, 불변 trigger, 소유자 분리, Golden 원천부터 API까지의 연결이다. 현재 실행된 결과의 실제 건수는 `STATUS.md`에 기록한다. 원천 fixture 전용 단위 검증 4개는 실행하여 통과했으며, Phase 4 전체 regression 결과를 이 문서에서 선행 선언하지 않는다.

현재 금액 범위는 KRW 단일 통화, 명시적 잔액·거래·소득·supplemental 입력이다. 카드/대출 스케줄을 실기관 API에서 가져왔다는 주장을 하지 않는다. 검증 연소득은 Public MyData MOCK이며 실제 LIVE_TEST는 별도 credential 준비 후 검증 대상이다.

최종 Phase 4 보강: demo 구성은 `server.address=127.0.0.1` 또는 `::1`을 시작 전에 필수 검증한다. latest API는 immutable snapshot과 별도로 `currentAssessment`에 최신 평가 ID·상태, 이전 정상값 사용 여부와 stale 경고를 제공한다.
