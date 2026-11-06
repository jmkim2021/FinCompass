# FinCompass Agent

**개인 금융 데이터와 대출상품 정보를 바탕으로 주택 구매·대출 시나리오를 계산하고, 결과의 근거를 설명하는 금융 의사결정 데모입니다.** LoanOps의 상품 데이터 파이프라인을 포함한 저장소에 개인 데이터 연결, 재무 분석, 시뮬레이션, 상품 후보 비교, 대화형 화면을 확장했습니다.

## 왜 만들었나

상품 목록과 금리만으로는 “지금 집을 사면 매달 얼마가 남는가”, “금리가 오르면 감당할 수 있는가”를 판단하기 어렵습니다. 개인 자산·부채·현금흐름과 상품 조건을 같은 시점의 근거로 묶고, 계산 과정과 빠진 정보를 확인할 수 있도록 만들었습니다.

## 담당한 구현

- Open Banking 테스트 환경과 재생 데모 데이터를 위한 연결 계층, Public MyData 계약 형태의 목업, 개인 금융 원천·동의·검증 이력을 구현했습니다.
- 계좌·거래·소득 근거를 표준 형태로 정리하고 Financial Twin 지표를 계산했습니다. 관측한 지출과 모델에 반영한 의무 지출을 구분하고, 결측이나 오래된 데이터는 경고로 남깁니다.
- 원리금균등 상환, 주택 구매 전후 현금흐름, 금리·소득·지출 스트레스 테스트와 목표 월여유금액에 맞춘 주택 가격 경계값 탐색을 구현했습니다. 금액 계산은 Java의 `BigDecimal`과 버전이 기록된 규칙으로 처리합니다.
- LoanOps에서 검증·게시된 상품 조건을 읽어 필수 자격과 정보 누락을 먼저 확인한 뒤, 비교 가능한 후보만 점수화했습니다. 후보별 원천 데이터와 사용한 계산 가정을 함께 보존합니다.
- Agent가 정해진 서비스 도구를 호출해 결과를 설명하도록 구성했습니다. 모델이 금융 숫자를 직접 만들지 않도록 결과의 숫자를 도구 출력과 대조하고, 실행 이력을 남깁니다.
- React 소비자 화면과 기존 LoanOps 운영 화면을 함께 제공해 연결 상태, 시나리오, 후보, 위험 신호와 계산 근거를 확인할 수 있게 했습니다.

## 실제 데이터와 데모의 범위

기본 실행은 외부 금융 계정이나 LLM 키가 필요 없는 재생 데모입니다. 데모 상품은 실제 기관 상품이 아닌 합성 데이터이며, 화면과 응답에서 `DEMO_PRODUCT_FIXTURE`로 구분합니다. Open Banking 실제 테스트 호출, 정부 Public MyData 연동, OpenAI 키를 사용한 실제 모델 호출은 이 저장소에서 검증되지 않았습니다. 따라서 결과는 대출 승인이나 상품 추천이 아니라 입력 조건을 기준으로 한 시뮬레이션입니다.

## 코드 구성

```text
src/main/java/com/loanops/           LoanOps와 FinCompass 백엔드
src/main/resources/db/migration/    Flyway 데이터베이스 변경 이력
src/main/resources/fixtures/        재생 데모용 입력
src/test/                           계산·데이터·API 검증
frontend/src/consumer/             개인 금융 화면
frontend/src/views/                LoanOps 운영 화면
scripts/run-golden-demo.sh         로컬 데모 실행
```

기존 LoanOps 코드를 이 저장소에 포함했기 때문에 상품 수집·품질 검증·현재 게시 데이터 조회 흐름도 함께 실행됩니다. 개인 데이터와 상품 데이터는 별도 경로로 처리하고, 시뮬레이션과 후보 비교에서 필요한 근거만 결합합니다.

## 실행 조건과 로컬 데모

JDK 21, Node.js 20.19 이상, Docker Compose, OpenSSL이 필요합니다. 첫 번째 터미널에서 아래 스크립트를 실행합니다. 스크립트는 데모 전용 PostgreSQL 볼륨을 매번 초기화하고 백엔드를 계속 실행합니다.

```bash
./scripts/run-golden-demo.sh
```

두 번째 터미널에서 프론트엔드를 실행합니다.

```bash
cd frontend
npm ci
npm run dev
```

화면은 `http://localhost:5173/#/fincompass/dashboard`에서 열립니다. 운영 데이터 화면은 `#/admin`입니다. 실제 연동용 설정은 `.env.example`을 참고하며, 비밀값은 저장소에 넣지 않습니다.

## 사용 예시

대시보드에서 시나리오 화면으로 이동해 구매가격·보유 현금·금리·기간을 입력하면 구매 전후 월여유금액과 상환액을 비교할 수 있습니다. 스트레스 화면에서는 금리 상승과 소득 감소가 결과에 미치는 영향을 확인하고, 근거 화면에서 사용한 데이터·가정·계산 규칙을 추적할 수 있습니다. 기본 데모의 모든 상품은 합성 데이터로 표시됩니다.

## 검증

```bash
./gradlew test
cd frontend && npm test -- --run && npm run typecheck && npm run build
```

백엔드 테스트는 PostgreSQL Testcontainers와 고정된 입력으로 데이터 승격, 계산값, 상품 비교, Agent 도구 호출을 확인합니다. 외부 금융 API나 LLM 호출 없이 실행되도록 구성했습니다.

## 실행 중 문제가 생기면

- 스크립트가 Docker 오류로 종료되면 Docker Desktop을 시작하고 다시 실행합니다. 로컬 `55432` 포트를 다른 프로그램이 사용 중이면 `POSTGRES_PORT`를 다른 값으로 지정합니다.
- 실제 금융기관 연결이나 LLM 응답이 보이지 않는 것은 기본 데모의 정상 동작입니다. 기본 모드는 `DEMO_REPLAY`와 결정론적 Agent입니다.

## 문의·라이선스·변경 이력

질문이나 오류는 [GitHub Issues](https://github.com/jmkim2021/FinCompass/issues)에 남길 수 있습니다. 코드는 [MIT 라이선스](LICENSE)를 따릅니다. 변경 내역은 [커밋 기록](https://github.com/jmkim2021/FinCompass/commits/main)에서 확인할 수 있습니다.
