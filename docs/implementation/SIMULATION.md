# Financial Simulation — Phase 5

## 구현 범위와 의존 관계

`com.loanops.fincompass.simulation.SimulationEngine`은 저장된 Financial Twin과 명시적 시나리오 입력만으로 계산하는 순수 backend 엔진이다. 외부 API, LLM, frontend 계산에 의존하지 않는다. `ScenarioService`가 소유자 범위의 frozen snapshot을 조회하고 엔진을 호출하며, `ScenarioStore`가 입력·결과를 암호화하여 보존한다. 중앙 규칙 버전은 `SimulationEngine.RULE_VERSION = FINANCIAL_SIMULATION_V1`이다.

지원하는 대출 상환은 `AMORTIZING` 원리금균등뿐이다. 이 결과는 재무적 영향의 추정이며 대출 승인·가입 보장·공식 대출 한도가 아니다.

## 금리·금액과 원리금균등 정책

모든 금액은 `BigDecimal`, KRW이며 입력은 비음수·소수점 최대 2자리·최대 `999999999999999999.99`이다. 연 이율은 decimal fraction이다. 예를 들어 4.5%는 `0.045`, +2 percentage points는 `0.02`이다. 연 이율과 shock 비율은 0~1 범위, 소수점 최대 16자리이다. 기간은 1~600개월이다.

월 이율 r = 연 이율 ÷ 12, 원금 P, 상환횟수 n에 대해 다음 산식을 사용한다.

```
regular payment = P × r × (1 + r)^n ÷ ((1 + r)^n − 1)
zero rate payment = P ÷ n
```

중간 계산은 `MathContext.DECIMAL128`(34 유효숫자), 금액 반올림은 `HALF_EVEN` scale 2이다. 매월 잔액에 월 이율을 곱한 이자를 scale 2로 확정하고, 납입액에서 이자를 뺀 금액을 원금에 반영한다. 마지막 납입액은 남은 원금 + 해당 월 이자로 조정하여 잔액을 정확히 0으로 만든다. 따라서 총이자는 단순히 정규 월납입액×기간−원금으로 산출하지 않고 **전체 상환표의 실제 이자 합계**를 쓴다.

`annualDebtService`는 첫 `min(12, termMonths)`회 실제 납입액 합계이다. 3개월 대출에 12개월 납입액을 가정하지 않는다. 예를 들어 무이자 원금 100원/3개월이면 납입 33.33, 33.33, 33.34원, 연간 상환액과 총상환액은 모두 100.00원이다.

결과에는 정규 월납입액, 연간 상환액, 총상환액, 총이자, 월별 opening/interest/principal/payment/closing, 입력, 경고를 담는다. 부채상환 부담은 `(기존 예정 월상환액×12 + 신규 대출 첫해 상환액) ÷ 검증 연소득`이다. 소득 0이나 소득·기존상환 context 결측은 null과 명시적 경고로 처리한다.

## 주택구매 입력과 Before/After

`HomeInput`은 financialSnapshotId, housePrice, cashContribution, mortgageAmount, annualInterestRate, termMonths, repaymentType, explicitCosts, adjustment를 가진다. 현금 투입액은 **명시적 부대비용을 포함한 전체 closing cash**이다.

```
cashContribution + mortgageAmount = housePrice + explicitCosts
```

위 조달금액 일치와 기존 유동자산·명시적 저축·사전 채무상환 후 현금의 충분성을 검증한다. 집값은 양수여야 한다. 주택취득세·중개료·이사비용 등을 임의 추정하지 않는다. 비용이 없거나 0이면 `ACQUISITION_COSTS_EXCLUDED_OR_EXPLICITLY_ZERO`를 표시한다.

사전 adjustment는 기존 채무상환액, 상환 후 해제되는 월상환액, 지연 개월 수, 명시적 월저축액이다. 채무상환액이 기존 부채를 넘거나 해제 상환액이 기존 상환액을 넘을 수 없다. 일부 채무만 갚고 전체 월상환액을 없앨 수 없으며, 전액 상환이면 기존 상환액의 전액 해제를 명시해야 한다. delayMonths는 0~120이다.

```
추가저축 = explicitMonthlySavings × delayMonths
구매후 유동자산 = 기존 유동자산 + 추가저축 − 사전채무상환 − closing cash
구매후 총자산 = 기존 총자산 + 추가저축 − 사전채무상환 − closing cash + 주택가격
구매후 부채 = 기존 부채 − 사전채무상환 + 신규주택담보대출
구매후 순자산 = 구매후 총자산 − 구매후 부채
구매후 월상환액 = 기존 월상환액 − 명시적 해제액 + 신규 정규 월납입액
구매후 월여유현금흐름 = 관측 월소득 − 생활비 − 구매후 월상환액 − 고정의무
```

Before/After는 동일 metric schema를 사용하며 Twin 11개 metric에 `MONTHLY_DEBT_SERVICE`를 추가한다. 자산·부채·유동자산·순자산·상환액·free cashflow·비상자금·상환부담·부채/자산을 일관되게 비교한다. 원 관측 `OBSERVED_MONTHLY_*`는 역사적 관측값으로 보존하고, After의 현금흐름·부채상환을 예측치로 구분한다.

비상자금 개월 수의 분모는 명시적 월 필수 지출이다. 부채상환이나 추가 고정의무를 자동 합산한 전체 생존비용이 아니다. 기존 주거비는 입력이 명시적으로 바뀌지 않는 한 유지된다. 지연 중 소득 상승·주택가격 변화·기존대출 자연상환을 임의 예측하지 않는다. 음수 free cashflow와 유동성 소진은 정상 계산 결과로 보존하면서 별도 경고를 낸다.

## Stress Engine

기본 grid는 다음 11개 가정이다.

- 주택담보대출 금리 +1, +2, +3 percentage points.
- 소득 −10%, −20%, −30%.
- 생활비/필수지출 +10%, +20%.
- 구매 후 유동자산 −20%, −30%.
- 금리 +2pp + 소득 −20% + 지출 +10% + 유동자산 −20% 복합 shock.

각 shock는 기존 snapshot이나 base scenario를 바꾸지 않고 동일 엔진으로 재계산한 별도 결과를 만든다. 금리 shock는 신규 mortgage에 적용하며 기존 대출의 예정 월상환액은 고정한다. 이는 대출의 실제 변동금리 계약을 단정하는 것이 아니라 가상의 충격이다.

소득 shock는 **관측 월소득과 검증 연소득 각각**에 같은 감소율을 적용하여 예상 free cashflow와 상환부담을 계산한다. 원 관측 metric은 수정하지 않는다. 지출 shock는 생활비와 필수지출에 적용하고 별도 고정의무는 고정한다. 유동성 shock는 주택구매가 완료된 뒤 남은 유동자산에 적용한다.

`riskAssetDrawdown`도 명시적 custom shock로 지원한다. 주택이나 기타 자산 전체를 위험자산으로 추정하지 않는다. frozen snapshot에 `RISK_ASSET_COMPONENT` fact가 있어야 하며 그 합계는 `OTHER_ASSET` 이하이어야 한다. 위험자산 입력이 없으면 총자산·순자산·관련 비율은 unavailable, `RISK_ASSET_COMPONENT_UNKNOWN` 경고로 반환한다. Golden은 위험자산을 공급하지 않으므로 기본 grid는 유동성 drawdown을 사용한다. 위험자산 −20%/−30%가 필요하면 `riskAssetDrawdown=0.20/0.30`을 명시한다.

사용자 API는 1~20개의 고유한 nonblank label을 가진 shock를 허용한다. 순수 엔진의 내부 상한은 50개이다. Monte Carlo, 확률 예측, LLM 수치 생성은 없다. 감소율 100%로 분모가 0이 되면 해당 비율은 null/INSUFFICIENT_DATA이다.

## Golden 시나리오와 대안

기준 snapshot은 `GOLDEN_HOME_BUYER.md`의 현금 1.2억/기존부채 3천만/관측 월소득 480만/생활비 280만/예정상환 60만/고정의무 20만/필수지출 220만/검증 연소득 6,600만 원이다.

`ScenarioExamples`가 다음 **명시적 합성 가정**을 제공한다. 네 대안 모두 연 4.5%, 360개월, AMORTIZING, 부대비용 0이다. 이 값은 모든 고객에게 적용할 기본 추천 조건이 아니다.

- A 지금 구매: 집값 700,000,000 / 현금 110,000,000 / 담보대출 590,000,000원.
- B 가격 낮추기: 집값 600,000,000 / 현금 110,000,000 / 담보대출 490,000,000원.
- C 기존 신용대출 우선 상환: 30,000,000원 선상환·월 600,000원 상환의무 해제 후, 집값 700,000,000 / 현금 80,000,000 / 담보대출 620,000,000원.
- D 12개월 미루기: 매월 1,200,000원씩 **명시적으로** 저축한다고 가정해 14,400,000원을 추가한 후, 집값 700,000,000 / 현금 124,400,000 / 담보대출 575,600,000원. 미래 저축을 보장하는 예측이 아니다.

독립 Python Decimal 산식 oracle과 대조할 기준값은 다음과 같다. 이 목록 자체를 Java 테스트 실행 PASS로 간주하지 않는다. 실제 실행 상태는 `STATUS.md`와 검증 결과를 따른다.

- A: 정규 월납입 **2,989,443.33원**, 총상환 **1,076,199,597.17원**, 총이자 **486,199,597.17원**. 마지막 납입은 **2,989,441.70원**.
- A 구매후: 총자산 **710,000,000원**, 부채 **620,000,000원**, 유동자산 **10,000,000원**, 순자산 **90,000,000원**, 월상환 **3,589,443.33원**, 월여유현금흐름 **−1,789,443.33원**, 비상자금 **4.54545455개월**, 부채상환 부담 **0.65262606**, 부채/자산 **0.87323944**.
- A +2pp: 정규 월납입 **3,729,201.34원**, 총이자 **752,512,480.76원**, 월여유현금흐름 **−2,529,201.34원**, 부채상환 부담 **0.78712752**.
- A 복합 shock: 월여유현금흐름 **−3,769,201.34원**, 부채상환 부담 **0.98390940**, 비상자금 **3.30578512개월**.
- B: 정규 월납입 **2,482,758.02원**, 총이자 **403,792,885.83원**, 월여유현금흐름 **−1,282,758.02원**.
- C: 정규 월납입 **3,141,448.92원**, 총이자 **510,921,612.21원**, 월여유현금흐름 **−1,341,448.92원**. 기존 상환의무가 해제되지만 현금 선상환 때문에 신규 mortgage는 커진다.
- D: 정규 월납입 **2,916,480.64원**, 총이자 **474,333,032.72원**, 월여유현금흐름 **−1,716,480.64원**, 구매후 순자산 **104,400,000원**.

네 대안 모두 월여유현금흐름이 음수이다. B나 C가 상대적으로 덜 나쁘다는 사실을 안전·승인가능·최적이라는 결론으로 바꾸지 않는다. 세금·취득비용이 제외되어 있다는 한계도 같이 유지한다.

## 저장·재생과 API

V12는 `scenario`, `scenario_assumption`, `scenario_result`를 추가한다. 기존 V1~V11을 수정하지 않는다. scenario는 소유자, frozen financialSnapshotId, kind(`LOAN/HOME/STRESS/COMPARISON`), parent scenario, calculationRuleVersion, simulationRuleVersion, createdAt을 가진다. 가정과 전체 결과는 기존 cipher로 context-bound 암호화하고 결과 content hash를 저장한다. 세 테이블의 UPDATE/DELETE는 불변 trigger로 거부한다.

STRESS는 원래 HOME scenario ID를 참조한다. 비교는 동일 snapshot·동일 계산/시뮬레이션 버전의 서로 다른 HOME scenario 2~10개만 허용한다. 비교 결과에는 입력 scenario ID와 이미 저장된 결과를 보존한다. GET은 최신 원천이나 상품을 다시 읽어 재계산하지 않는다. 새로운 실행은 새 ID를 생성하지만 동일 입력·버전의 금융 결과는 같다.

다음 HTTP 경로는 `fincompass.demo.enabled=true`에서만 활성화되고 고정 로컬 demo owner로 범위를 제한한다. production 인증을 구현했다고 주장하지 않는다.

- `POST /api/v1/scenarios/loan`: `{financialSnapshotId, loan:{principal, annualInterestRate, termMonths, repaymentType}}`.
- `POST /api/v1/scenarios/home-purchase`: `HomeInput`.
- `POST /api/v1/scenarios/{id}/stress-test`: 생략 가능한 `{shocks:[...]}`. 생략하면 기본 grid.
- `POST /api/v1/scenario-comparisons`: `{scenarioIds:[...]}`.
- `GET /api/v1/scenarios/{id}`: 저장된 immutable 결과.
- `GET /api/v1/scenarios`: 소유자의 최근 최대 100개, createdAt/ID 기준 결정적 정렬.

잘못된 입력과 지원하지 않는 상환방식은 오류로 처리한다. 없는 ID나 다른 소유자의 ID는 조회할 수 없다. body의 금액은 decimal JSON 숫자로 보내며 금리는 fraction을 사용한다.

## 검증 기록 원칙

이 문서를 작성하면서 별도 Python Decimal(50자리) 상환표 oracle을 실제 실행하여 golden A–D, +1/+2/+3pp, 무이자 마지막 납입, 소득·생활비·유동성·복합 shock 기준값을 산출했다. Java 엔진의 DECIMAL128/월 단위 반올림 정책과 대조하였다. 상세 산식 작업 파일은 개발 검증 자료이며 운영 계산 경로가 아니다.

Java unit/integration/API, 불변 저장, 소유자 분리, 전체 backend regression 결과는 실제 실행이 완료된 뒤 `STATUS.md`에 기록한다. 실행하지 않은 검증을 PASS로 기록하지 않는다.
