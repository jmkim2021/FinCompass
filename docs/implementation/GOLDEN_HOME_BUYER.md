# Golden Home Buyer — 명시적 데모 입력 계약

## 목적과 적용 범위

`golden-homebuyer-v1`은 PDF p.24의 통합 persona를 재현하기 위한 **합성 데모**이다. 은행 실계좌나 실사용자의 자산을 뜻하지 않는다. Phase 1의 `openbanking/demo-v1` 잔액 150만 원·거래 28건 fixture는 변경하지 않았다. Golden fixture는 `openbanking/golden-homebuyer-v1/{balance,transactions}.json`에 따로 있다.

사용자는 `golden-homebuyer`라는 로컬 demo 소유자이다. 이 사용자의 Open Banking와 Public MyData 연결은 동일한 canonical financial user에 명시적으로 연결한다. 다른 데이터셋의 개인을 추정하여 join하지 않는다.

`fincompass.demo.enabled=true`일 때 공통 Clock은 **2026-09-21T10:00:00Z**에 고정된다. 이는 과거 기준일의 반복 가능한 시뮬레이션이며 실제 오늘의 계좌 상태를 표시하지 않는다. Open Banking는 `DEMO_REPLAY`, Public MyData는 `MOCK`이다. Golden bootstrap는 LIVE_TEST 모드에서 실행하지 않는다.

## 원천을 통과하는 입력

### Open Banking 잔액

- 값: 장부 잔액 120,000,000원, 가용 잔액 120,000,000원.
- 원천: 합성 `golden-homebuyer-v1/balance.json`, `fixture_origin=SYNTHETIC_GOLDEN_HOME_BUYER_V1`.
- sourceType: `OPEN_BANKING_TEST`; sourceMode: `DEMO_REPLAY`.
- asOf: `2026-09-21T00:00:00Z` (`api_tran_dtm=20260921090000000`, 서울 시간).
- 용도: 유동자산과 총자산의 현재 잔액 입력. 거래의 after-balance 합산으로 자산을 만들지 않는다.
- 통화: 원래 Open Banking 응답 계약에는 통화가 없으므로 canonical currency는 임의로 채우지 않는다. 별도 `ACCOUNT_CURRENCY_KRW` 사용자 assertion이 있어야 KRW 계산에 사용한다.

### Open Banking 관측 거래

조회 범위는 `2026-08-01`부터 `2026-08-31`까지 **완전한 한 달**이다. 원천에는 총 32건이 있고 replay provider가 25건과 7건의 두 페이지로 반환한다. sync 완료, 원문 암호화 보존, mapper, 중복 식별, canonical normalization과 품질 검증을 모두 거친 후 Twin이 읽는다.

- 생활비 28건: 8월 1~28일 매일 100,000원 출금. 합계 **2,800,000원**. fixture label `GOLDEN_V1_LIVING_EXPENSE` → `LIVING_EXPENSE`.
- 관측 소득 1건: 8월 25일 4,800,000원 입금. label `GOLDEN_V1_INCOME` → `INCOME`.
- 부채상환 1건: 8월 26일 600,000원 출금. label `GOLDEN_V1_DEBT_SERVICE` → `DEBT_SERVICE`.
- 이체 2건: 8월 27일 100,000원 출금, 8월 28일 100,000원 입금. label `GOLDEN_V1_TRANSFER` → `TRANSFER`. 내부 이동을 나타내는 명시적 fixture 설정이며 소득·생활비에 포함하지 않는다.
- 거래 이전 장부 잔액은 118,600,000원이고 합계 현금 이동 +1,400,000원으로 마지막 after-balance는 120,000,000원이다. 9월 21일 잔액 fixture와 일치하도록 합성한 것이다.

이는 일반 은행 거래 설명에서 급여·채무를 추론하는 규칙이 아니다. Golden 원천에 미리 정의된 네 가지 label을 코드의 allowlist로 분류한 뒤 실제 canonical transaction UUID → category를 Twin 입력에 고정한다. 알 수 없는 label이면 bootstrap가 실패한다. 일반 입력에서 명시적 분류가 없으면 `UNCLASSIFIED`로 남는다. merchant나 은행이 제공하지 않은 category를 canonical 레코드에 꾸며 넣지 않는다.

관측 월 소득 **480만 원은 연소득 6,600만 원 ÷ 12가 아니다**. 별도로 정한 합성 현금흐름 입력이다. 세전 소득, 실수령 소득, 비정기 입금의 차이를 추정하는 세금 계산도 하지 않는다.

### Public MyData 소득·고용

기존 `fixtures/public-mydata/main-home-buyer-v1.json` (`PMD-DEMO-001`, 내부 계약 `pmd-minimal-v1`)을 재사용한다.

- INCOME_CERTIFICATE: verifiedAnnualIncome **66,000,000원**, incomeYear **2025**, currency `KRW`.
- EMPLOYMENT_ELIGIBILITY: employmentStatus **EMPLOYED**. 고용 evidence에는 연소득을 중복하여 넣지 않는다.
- asOf: **2026-09-01T00:00:00Z**.
- sourceType: `PUBLIC_MYDATA_MOCK`; sourceMode: `DEMO_REPLAY`.
- 용도: 검증 소득 evidence 계약과 고용 상태의 모의 입력. 연간 부채상환 부담의 분모는 이 연소득이다.

여기서 `verifiedAnnualIncome`은 계약의 필드명이다. 실제 공공기관에서 검증하거나 조회한 소득이라는 뜻이 아니다. 화면·응답에는 MOCK 출처를 함께 표시해야 한다.

## 추가로 명시한 입력

`GoldenDemoConfiguration.facts()`가 다음 fact를 별도로 만든다. 모든 fact의 sourceType은 `USER_INPUT`, sourceMode는 `DEMO_REPLAY`, evidence ID는 `golden-homebuyer-v1:<KIND>`, 기준 시각은 고정 demo 시각이다. fact UUID는 이 evidence ID에서 결정적으로 만들고 내용·사용자 소유권을 확인하여 재사용한다. Open Banking의 지원하지 않는 대출·카드 기능을 성공한 것처럼 만들지 않는다.

- `EXISTING_LOAN_BALANCE=30000000`: 기존 신용대출 잔액. PDF persona의 3천만 원. 총부채 계산에 사용한다.
- `SCHEDULED_MONTHLY_DEBT_SERVICE=600000`: 기존 대출의 월 예정 상환액. PDF에 상환 스케줄이 없으므로 이번 데모에서 명시한 가정이다. 월 여유 현금흐름과 연간 상환 부담에 사용한다.
- `MONTHLY_FIXED_OBLIGATION=200000`: 생활비·부채상환에 포함하지 않은 추가 월 고정 의무. 이번 데모의 명시적 가정이며 Open Banking 거래에 중복 삽입하지 않았다.
- `MONTHLY_ESSENTIAL_EXPENSE=2200000`: 월 필수 지출. 비상자금 개월 수의 분모이다. 전체 생활비 280만 원의 부분 집합이며 자유 현금흐름에서 별도로 재차 차감하지 않는다.
- `OTHER_ASSET=0`: 이 합성 persona에는 별도로 입력한 비유동/투자 자산이 없다는 명시적 선언. 일반 사용자 데이터가 없다는 사실을 자산 0으로 추론하지 않는다.
- `ACCOUNT_CURRENCY_KRW=1`: 합성 계좌 금액을 KRW로 해석한다는 사용자 assertion. 원 단위 자산 1원을 뜻하지 않는다.
- `EXISTING_LOAN_ANNUAL_RATE=0.061`: 기존 대출의 연 이자율 **6.1%**. 연 단위 decimal fraction이다. 향후 대환 대안의 입력 근거이며 현재 Twin이 상환액을 이 금리에서 추정하지 않는다.

금액 fact는 원 단위이다. `ACCOUNT_CURRENCY_KRW`는 논리 assertion, `EXISTING_LOAN_ANNUAL_RATE`는 연 이율 fraction으로 각각 kind가 단위를 정의한다. fact의 KRW 문맥 필드는 이 두 값을 원 금액으로 바꾸는 의미가 아니다.

PDF의 월 카드 청구액 **147만 원**은 생활비 280만 원 안에 포함된 참고 부분 집합으로 해석한다. 현재 Open Banking adapter에 card-bill capability가 없으므로 card_bill을 합성하여 넣지 않고, 총지출에 147만 원을 추가로 더하지 않는다. 실제 카드 증빙이 필요한 판단에서는 지원되지 않는 입력으로 표시해야 한다.

주택 목표 가격 **700,000,000원**은 Phase 5 시나리오의 사용자 입력이며 구매 전 Twin의 자산이 아니다. 대출금·현금 투입·금리·기간·비용은 시나리오에서 별도로 명시하고 계산해야 한다.

## 계산 기대값과 검증 구분

이 입력 계약에 따른 Twin 산식 기대값은 다음과 같다. 실제 테스트 실행 결과와 집계는 `STATUS.md` 및 phase 검증 로그가 기준이며, 이 문서의 기대값 목록 자체를 테스트 PASS로 간주하지 않는다.

- 총자산/유동자산: 각각 120,000,000원.
- 총부채: 30,000,000원, 순자산: 90,000,000원.
- 관측 월 소득: 4,800,000원.
- 관측 월 유출: 생활비 2,800,000 + 관측 부채상환 600,000 = **3,400,000원**. 이체와 supplemental 고정의무는 관측 유출에 포함하지 않는다.
- 월 여유 현금흐름: 4,800,000 − 2,800,000 − 600,000 − 200,000 = **1,200,000원**. 관측 부채상환과 예정 부채상환을 두 번 차감하지 않는다.
- 비상자금 개월 수: 120,000,000 ÷ 2,200,000 = **54.54545455개월**.
- 연간 부채상환 부담: 600,000 × 12 ÷ 66,000,000 = **0.10909091**. 규제 DSR이나 승인 가능성이 아니다.
- 부채/자산: **0.25000000**, 유동자산/자산: **1.00000000**.

원천 fixture 단위 검증 `GoldenHomeBuyerReplayTest`는 2026-09-21 실행에서 **4/4 PASS**, 실패·skip 0이었다. 별도 fixture, 이전 fixture 보존, 25+7 pagination, 월 합계, 기간 격리, 경로 allowlist를 검사한다. 전체 Phase 4 회귀 및 저장/API 검증은 통합 실행 후 `STATUS.md`에 기록한다.

## 재현성과 경계

`GoldenHomeBuyerSources.seed(owner)`는 동일 소유자의 유효 동의와 Golden 계좌/PMD fixture를 찾고 이미 검증된 원천은 재사용한다. bootstrap runner는 이미 저장된 Financial Twin이 있으면 새로운 원천을 수집하지 않는다. snapshot fingerprint에는 canonical record/provenance/validation ID와 값, 입력 fact ID와 내용, 분류, 원천 기준일, rule version을 담는다.

빈 DB마다 UUID와 암호화 ciphertext는 달라질 수 있다. 동일 fixture와 규칙의 **금융 결과가 동일**하다는 것이 재현성의 의미이며 서로 다른 DB의 모든 ID·암호문·fingerprint가 같다고 주장하지 않는다. 실제 금융결제원 호출은 이 시나리오에서 하지 않는다.

최종 Phase 4 보강: demo 구성은 `server.address=127.0.0.1` 또는 `::1`을 시작 전에 필수 검증한다. latest API는 immutable snapshot과 별도로 `currentAssessment`에 최신 평가 ID·상태, 이전 정상값 사용 여부와 stale 경고를 제공한다.
