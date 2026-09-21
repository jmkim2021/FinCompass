# Product Match Score V1

`PRODUCT_MATCH_SCORE_V1`은 적격성이나 승인 확률이 아니라 검토 후보 간 호환성을 설명하는 결정론적 점수다. Hard Filter와 필수 데이터 검사를 통과한 `MATCHED_FOR_REVIEW` 후보에만 계산한다. 필수 정보가 빠진 후보의 score와 rank는 `null`이다.

가중치는 설정 객체에 35/25/20/10/10으로 고정한다.

- Affordability Fit 35%: `clamp(100 × (1 − 월납입액 / 명시 최대월납입액), 0, 100)`.
- Interest Cost 25%: 동일 run의 score 가능 후보 총이자 min/max 안에서 낮을수록 100에 가까운 선형 점수다. 모두 같으면 100이다.
- Stress Resilience 20%: V1은 동일 Phase 5 엔진의 +2%p 충격에서 구매 후 월여유 현금흐름이 0 이상이면 100, 아니면 0이다.
- Preference Fit 10%: 명시한 상환 선호 충족 비율이다. 선호가 없으면 중립값 50이다.
- Data Quality/Freshness 10%: serving/품질 통과 후 sourceObservedAt 기준 90일 이내 100, 365일 이내 60, 이후 20이다. 관측일이 없으면 필수 데이터 부족이다.

각 component는 rawScore, weightPercent, weightedScore를 저장한다. total은 weightedScore 합계이며 HALF_EVEN 4자리다. 동점은 simulation rate, loanProductId, productTermId 순으로 안정적으로 정렬한다. 순위는 승인·추천·최적 상품의 의미가 없다.

금리 정책 `PRODUCT_RATE_V1`은 유효한 대표 평균금리, maxRate, 유일한 minRate 순으로 선택하고 선택 경고를 남긴다. 원천 percent는 계산 전에 annual fraction으로 나눈다. 기존 LoanOps 규칙대로 평균을 min/max 사이로 강제하지 않는다.
