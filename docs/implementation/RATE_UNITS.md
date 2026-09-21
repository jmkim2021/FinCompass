# Annual interest rate 단위 결정

상태: Phase 1 적용, 2026-09-21.

- source/raw 원본 값과 기존 상품 필드는 변환하지 않는다. 과거 normalized 상품 데이터의 단위를 소급해 바꾸지 않는다.
- 향후 canonical/calculation 경계에서는 `com.loanops.finance.AnnualInterestRate`의 `decimalFraction`을 사용한다.
- source가 percent를 제공하면 `fromPercent(new BigDecimal("4.5"))` → 0.045, 6.1 → 0.061.
- source가 fraction이면 `fromDecimalFraction(new BigDecimal("0.045"))`. magnitude로 단위를 추정하지 않는다.
- 외부 표시 percent는 `percent()`로 명시적으로 변환한다. BigDecimal 기반으로 값 변환만 하며 반올림을 도입하지 않는다.
- null/음수는 허용하지 않는다. 100% 초과 여부는 향후 상품/계산 정책에서 판단하므로 이 value object는 임의 상한을 넣지 않는다.
- unit test에서 percent/fraction 양방향과 음수 거절을 실제 검증했다.

Phase 1 당시에는 대출 계산 엔진이나 개인 canonical 금리 필드가 없었다. 최종 구현에서 Simulation 입력과 stress delta는 decimal fraction(`0.045`=4.5%), 기존 LoanOps 상품 원천/표시는 percent number(`4.50`=4.5%)로 유지하며 Product Matching 경계에서 명시적으로 변환한다. 기존 상품 rate BigDecimal 전체를 일괄 리팩터링하지 않았다.

PublicationRun을 전체 immutable catalogue version으로 해석하지 않는다. Phase 6에서 LoanProduct/ProductTerm/LoanRateObservation ID, source, observedAt, 실제 publication/run 참조를 함께 고정한다. 이번 phase는 해당 모델을 변경하지 않았다. PDF의 3.6억/4억/700M 예시를 하나의 fixture로 합치지 않았다.
