# FinCompass Consumer UI

기본 진입점은 `#/fincompass/dashboard`이며 기존 LoanOps 운영 콘솔은 `#/admin`으로 보존한다. React 19/Vite 기존 스택에서 hash route를 사용해 Data Connections, Financial Dashboard, Ask FinCompass Agent, Scenario Result, Matched Loan Candidates, Stress Test, Alternative Scenarios, Opportunities, Evidence/Calculation Trace 9개 화면을 제공한다.

화면은 `OPEN BANKING TEST`, `PUBLIC MYDATA MOCK`, `REAL PRODUCT DATA`, `USER INPUT`, `DERIVED`, `DEMO REPLAY`, `AI-HUB OFFLINE` 경계를 배지로 표시한다. 숫자가 없거나 availability가 불완전하면 0으로 바꾸지 않고 `확인 불가`를 보여준다. warning/limitation은 별도 패널로 항상 노출하며, 상품은 `Review Candidate` 용어만 사용한다.

Agent 화면의 Golden 입력은 사용자에게 보이는 명시적 폼 값이다. 응답은 가정, 도구 유래 설명, 경고, 한계를 분리하고 실행 추적으로 이동할 수 있다. Scenario·Stress·Alternatives 화면은 API가 저장한 결과만 표시하며 프런트엔드에서 금융 계산을 다시 하지 않는다. 기존 chart 의존성이 없으므로 접근 가능한 표·행 비교를 사용했고 새 테스트 프레임워크나 차트 패키지를 추가하지 않았다.

Phase 9 검증은 TypeScript typecheck, Vite production build, 브라우저 데스크톱 렌더링, 기존 백엔드 전체 회귀와 consumer API flow 통합 테스트로 수행한다. 모바일은 720px 이하에서 단일 열, 가로 메뉴, 축약 추적으로 전환한다.
