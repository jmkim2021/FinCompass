# FinCompass 포트폴리오 스크린샷 가이드

자동 브라우저 감사에서는 실제 localhost UI 렌더링을 확인했지만 브라우저 보안 정책상 캡처 바이트를 저장소 파일로 내보낼 수 없었다. 아래 순서로 실제 앱 화면을 1440×900 PNG로 캡처한다. mock 이미지를 만들지 않는다.

## 준비

1. root에서 `./scripts/run-golden-demo.sh`를 실행한다.
2. 별도 터미널에서 `cd frontend && npm run dev`를 실행한다.
3. `http://localhost:5173/#/fincompass/dashboard`를 1440×900으로 연다.
4. Agent 기본 7억원 질문을 한 번 실행해 scenario/match/opportunity/trace를 만든다.

## 캡처 목록

1. `01-financial-dashboard.png`: 2.2M 필수지출, 3.4M 관측 유출, 3.6M 모델 총유출.
2. `02-home-700m.png`: before/after와 −1,789,443.33원 월 여유.
3. `03-stress-grid.png`: +1/+2/+3%p와 복합 stress.
4. `04-product-incomplete.png`: `INSUFFICIENT_DATA`, score/rank 없음.
5. `05-product-complete.png`: `DEMO PRODUCT FIXTURE`, Review Candidate #1–#4와 score breakdown.
6. `06-agent-conversation.png`: grounded answer, assumptions, warnings, limitations.
7. `07-evidence-trace.png`: source/reference ID, tool call, rule version, formula.
8. `08-target-search.png`: `Simulation Boundary` 248.1M.
9. `09-data-connections.png`: DEMO/OPEN BANKING TEST/PUBLIC MYDATA MOCK/DEMO PRODUCT FIXTURE badges.

계좌 식별자, token, API key, ciphertext 또는 정부 evidence 식별자는 캡처에 포함하지 않는다.
