# FinCompass API

모든 소비자 API는 `fincompass.demo.enabled=true`에서 활성화되며 현재 loopback Golden owner로 제한된다. 금리는 연 단위 fraction(`0.045` = 4.5%), 금액은 KRW다. `null + availability/warning`은 결측이며 0으로 해석하지 않는다.

## Financial Twin

- `GET /api/v1/financial-snapshots/latest`
- `GET /api/v1/analytics/cashflow`
- `GET /api/v1/analytics/debt`
- `GET /api/v1/financial-snapshots/{id}`

응답은 `financialSnapshotId`, `asOf`, `status`, `calculationRuleVersion`, `input.sourceRefs`, `result.metrics`, `currentAssessment`를 포함한다. 세 latest/analytics 경로는 같은 frozen snapshot을 용도별 화면에 제공한다.

## Scenario

`POST /api/v1/scenarios/loan`

```json
{"financialSnapshotId":"<uuid>","loan":{"principal":590000000,"annualInterestRate":0.045,"termMonths":360,"repaymentType":"AMORTIZING"}}
```

`POST /api/v1/scenarios/home-purchase`

```json
{"financialSnapshotId":"<uuid>","housePrice":700000000,"cashContribution":110000000,"mortgageAmount":590000000,"annualInterestRate":0.045,"termMonths":360,"repaymentType":"AMORTIZING","explicitCosts":0,"adjustment":{"existingDebtRepayment":0,"releasedMonthlyDebtService":0,"delayMonths":0,"explicitMonthlySavings":0}}
```

- `POST /api/v1/scenarios/{id}/stress-test`, 기본 grid는 `{}`
- `POST /api/v1/scenario-comparisons`, body `{"scenarioIds":["<uuid>","<uuid>"]}`
- `GET /api/v1/scenarios/{id}`
- `GET /api/v1/scenarios`

Golden home 응답의 핵심 실제 값은 `homeResult.mortgage.regularMonthlyPayment=2989443.33`, `homeResult.after.MONTHLY_FREE_CASHFLOW.value=-1789443.33`이다.

## Product Matching

`POST /api/v1/loan-product-matches`

```json
{"scenarioId":"<home-scenario-uuid>","requiredLoanAmount":null,"maxMonthlyPayment":null,"preferredTermYears":null,"repaymentPreferences":["AMORTIZING"],"topK":5}
```

- `GET /api/v1/loan-product-matches/{id}`
- `GET /api/v1/loan-product-matches`

응답은 frozen product manifest, status, simulation result, score/rank, warnings/limitations와 계산·시뮬레이션·점수·금리 정책 버전을 담는다. Golden 4개 term은 `INSUFFICIENT_DATA`, score/rank null이다.

## Opportunities

`GET /api/v1/opportunities`는 활성 이벤트를 반환한다. 이벤트에는 `type`, `detectedAt`, snapshot/scenario/match ID, `ruleVersion`, `evidence{metric,actual,threshold,unit,productTermIds}`, warnings가 있다. 이미 Agent가 명시적 시나리오 이벤트를 만들었다면 GET은 이를 읽고 다른 시나리오로 재평가하지 않는다.

## Agent

`POST /api/v1/agent/chat`

```json
{"question":"7억 아파트를 사도 될까?","housePrice":700000000,"cashContribution":110000000,"mortgageAmount":590000000,"annualInterestRate":0.045,"termYears":30,"maxMonthlyPayment":null}
```

응답은 `agentRunId`, status, grounded answer, intent, snapshot/scenario/match ID, missingInputs, assumptions, warnings, limitations를 담는다. 필수 값이 없으면 `NEEDS_INPUT`이며 시나리오를 만들지 않는다.

`GET /api/v1/agent/runs/{id}/trace`는 model provider/version, tool 순서, params/result hash, 규칙 버전, 허용 숫자 evidence, 최종 설명과 fallback 여부를 반환한다. 민감 payload는 DB에서 암호화된다.
