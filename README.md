# LoanOps

LoanOps is a financial public-data integration and operations platform focused on correctness, traceability, data integrity, and operational reliability. This repository currently contains Phase 1 only: the executable backend foundation and verified PostgreSQL wiring.

## Phase 1 stack

- Java 21
- Spring Boot 3.5.16
- Gradle 8.14.3 Wrapper
- PostgreSQL 17.11
- Spring Data JPA
- Flyway
- Spring Boot Actuator
- springdoc OpenAPI 2.9.1
- JUnit 5 and Testcontainers 2.0.5
- Docker Compose

## Prerequisites

- JDK 21
- Docker Desktop or another Docker-compatible runtime with Compose

## Local setup

Create a local environment file and replace both password placeholders with the same private local value:

```bash
cp .env.example .env
```

Start PostgreSQL:

```bash
docker compose --env-file .env up -d postgres
docker compose --env-file .env ps
```

Export the application connection settings and run the backend:

```bash
set -a
source .env
set +a
./gradlew bootRun
```

The application intentionally has no hard-coded database credential fallback. It fails fast when `DATABASE_URL`, `DATABASE_USERNAME`, or `DATABASE_PASSWORD` is missing.

## Verification

Run the complete test suite. The integration tests start a real PostgreSQL 17.11 container and do not use H2:

```bash
./gradlew test
```

With the application running, verify Actuator health and OpenAPI:

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/v3/api-docs
```

Open Swagger UI at <http://localhost:8080/swagger-ui.html>.

Stop the local database without deleting its data:

```bash
docker compose --env-file .env down
```

Delete the local database volume only when intentionally resetting local data:

```bash
docker compose --env-file .env down --volumes
```

## Current phase boundary

Phase 1 does not include business entities, source executions, raw snapshots, official API clients, adapters, product or rate models, data-quality logic, quarantine, change detection, retries, security, audit, frontend, or AI features. Those capabilities belong to later approved phases.
