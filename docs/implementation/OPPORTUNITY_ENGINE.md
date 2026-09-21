# Opportunity Engine — Phase 7

`OPPORTUNITY_V1`은 LLM 없이 Financial Twin 이력, 저장된 Home scenario, 신뢰 상품 match 결과를 평가한다. 기본 threshold는 비상자금 3개월, 분석용 부채부담 0.40, 대환 검토 금리차 0.01이며 환경설정으로 바꾸고 ruleVersion과 함께 저장한다.

- `LIQUIDITY_RISK`: 현재 또는 최신 저장 시나리오의 월여유 현금흐름이 음수이거나 비상자금이 threshold 미만일 때 생성한다.
- `DEBT_BURDEN_WARNING`: 분석용 debt-service burden이 threshold를 초과할 때 생성한다. 규제 DSR 판정이 아니다.
- `RATE_REVIEW`: 두 개 이상의 COMPLETE Snapshot에서 검증 소득 증가 또는 총부채 감소가 확인될 때만 생성한다.
- `REFINANCING_REVIEW`: 기존 대출의 명시적 사용자 금리와 `MATCHED_FOR_REVIEW` 상품만 비교해 gap을 넘을 때 생성한다. 결측 상품이나 서울시 집계금리는 비교하지 않는다.

STALE/PARTIAL/INCOMPLETE Snapshot은 이벤트를 만들지 않는다. 경계값과 같은 경우도 초과/미만 규칙에 따라 생성하지 않는다. 이벤트의 type, snapshot/scenario/match ID, actual/threshold/unit, 상품 term ID, warning을 암호화된 append-only payload와 함께 저장한다. 같은 rule·근거 fingerprint는 unique key로 재호출해도 하나만 존재한다.

`GET /api/v1/opportunities`는 demo owner의 규칙을 재평가한 뒤 ACTIVE 이벤트를 반환한다. 이는 검토 신호이며 “대환해야 한다”, 규제 실패, 승인 가능성을 의미하지 않는다. Golden 7억원 시나리오는 구매 후 월여유 현금흐름이 음수이고 분석 부채부담이 0.40을 초과하므로 두 개의 review event가 기대된다.
