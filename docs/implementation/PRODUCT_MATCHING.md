# Loan Product Matching — Phase 6

매칭은 `Financial Twin + 사용자/시나리오 목표 + 기존 LoanOps serving catalog`를 결합한다. 별도 상품 catalog를 만들지 않는다. 순서는 정규화 요청, financing envelope, serving 조회, Hard Filter, Phase 5 동일 엔진 계산, +2%p stress, 점수, review rank다.

`ProductCatalogReader`는 serving product와 term을 source identity로 조인한다. product pointer와 term의 원래 parent가 다를 수 있으므로 서로 같다고 가정하지 않는다. 후보마다 product/term/term-parent ID, 양쪽 raw ID/hash, source run, publication run, 수집시각과 sourceSubmittedAt을 동결한다. `source_submitted_at`은 Asia/Seoul 원천 시각으로 해석하며 값이 없으면 관측일을 추정하지 않는다. 서울시 집계 LoanRateObservation은 특정 FSS 상품 금리로 사용하지 않는다.

`fixture_origin` 또는 `fincompass_demo` marker가 있는 합성 상품은 동일 LoanOps pipeline을 통과하지만 sourceType `DEMO_PRODUCT_FIXTURE`, sourceMode `DEMO_REPLAY`로 표시한다. `REAL_PRODUCT_DATA`는 synthetic marker가 없는 trusted source pipeline 레코드에만 사용한다.

Hard Filter는 품질/serving, 명시 금액 범위, 기간, 상환방식 불일치를 점수 전에 제거한다. amount/term/eligibility/정확한 AMORTIZING mapping 중 하나라도 없으면 `INSUFFICIENT_DATA`이고 score/rank는 null이다. 금리가 있으면 설명용 loan 계산은 가능하지만 적격하다고 표현하지 않는다. direct request는 최신 usable Twin으로 상환액을 계산하되 home scenario가 없으므로 구매 후 지표와 stress score를 만들지 않는다.

V13의 match run, candidate manifest, score component, candidate scenario result는 append-only다. 전체 Run payload와 후보 결과는 개인 데이터 암호화를 재사용하며 owner-scoped API만 읽는다. 이후 serving pointer가 바뀌어도 저장된 run은 재계산하지 않는다.

API는 demo loopback 모드에서 `POST /api/v1/loan-product-matches`, `GET /api/v1/loan-product-matches/{id}`와 목록 조회를 제공한다. 입력은 home scenarioId 또는 직접 requiredLoanAmount/maxMonthlyPayment/preferredTermYears를 받는다. 결과 용어는 Review Candidate이며 Best/승인/보장 표현을 사용하지 않는다.

Demo A의 기존 Golden 상품 원천에는 숫자 한도, 기간, 개인 적격성, `분할상환방식`을 AMORTIZING으로 확정할 근거가 없다. 따라서 이 4개 term은 rank 0개를 유지한다. 결측을 가짜 Top-K로 감추지 않는다.

## Phase 11 두 가지 Golden 매칭 데모

- 불완전 fixture 4개 term은 계속 `INSUFFICIENT_DATA`, score/rank null이다.
- 완전 합성 fixture `DEMO-FULL-1..4`는 amount 100M–700M, term 20–40년, `AMORTIZING`, 합성 eligibility/constraints를 명시한다. raw → normalization → quality → publication → serving 뒤 Hard Filter → candidate simulation → +2%p stress → score → rank를 실행한다.

Golden 7억원 scenario에서 완전 후보의 구매후 cashflow와 stress cashflow도 음수다. `MATCHED_FOR_REVIEW`와 `Review Candidate`는 상대 검토 순서이며 추천·최적·승인 가능성을 뜻하지 않는다.
