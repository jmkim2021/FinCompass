#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

for command in docker openssl java; do
  command -v "$command" >/dev/null 2>&1 || { echo "필수 도구를 찾을 수 없습니다: $command" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker가 실행 중이 아닙니다. Docker Desktop을 시작한 뒤 다시 실행하세요." >&2; exit 1; }

demo_db_password="$(openssl rand -hex 18)"
demo_encryption_key="$(openssl rand -base64 32)"

export POSTGRES_DB=loanops
export POSTGRES_USER=loanops
export POSTGRES_PASSWORD="$demo_db_password"
export POSTGRES_PORT="${POSTGRES_PORT:-55432}"
export DATABASE_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/loanops"
export DATABASE_USERNAME="$POSTGRES_USER"
export DATABASE_PASSWORD="$demo_db_password"
export OPEN_BANKING_ENCRYPTION_KEY="$demo_encryption_key"
export OPEN_BANKING_MODE=DEMO_REPLAY
export LOANOPS_API_ALLOWED_ORIGINS="${LOANOPS_API_ALLOWED_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173}"

demo_project="fincompass-golden-demo"
echo "[FinCompass] 전용 Docker project '$demo_project'와 전용 PostgreSQL volume을 초기화합니다."
echo "[FinCompass] 다른 compose project나 PostgreSQL volume은 변경하지 않습니다."
docker compose -p "$demo_project" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "$demo_project" up -d postgres
until docker compose -p "$demo_project" exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do sleep 1; done

echo "[FinCompass] Demo Mode: DEMO_REPLAY (합성/테스트 데이터, 외부 자격증명 없음)"
echo "[FinCompass] Backend: http://127.0.0.1:8080"
echo "[FinCompass] 다음 단계: 별도 터미널에서 'cd frontend && npm run dev' 후 http://localhost:5173 을 여세요."

exec ./gradlew bootRun --args='--spring.profiles.active=fincompass-demo,golden-products'
