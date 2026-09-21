#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

demo_db_password="$(openssl rand -hex 18)"
demo_encryption_key="$(openssl rand -base64 32)"

export POSTGRES_DB=loanops
export POSTGRES_USER=loanops
export POSTGRES_PASSWORD="$demo_db_password"
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export DATABASE_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/loanops"
export DATABASE_USERNAME="$POSTGRES_USER"
export DATABASE_PASSWORD="$demo_db_password"
export OPEN_BANKING_ENCRYPTION_KEY="$demo_encryption_key"
export OPEN_BANKING_MODE=DEMO_REPLAY

docker compose up -d postgres
until docker compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do sleep 1; done

exec ./gradlew bootRun --args='--spring.profiles.active=fincompass-demo,golden-products'
