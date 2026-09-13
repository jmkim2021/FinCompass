# LoanOps

LoanOps is a financial public-data integration and operations platform focused on correctness, traceability, data integrity, and operational reliability. The current implementation includes the backend foundation, external-source execution and raw-response preservation, and Phase 3 source-specific normalization.

## Stack

- Java 21
- Spring Boot 3.5.16
- Gradle 8.14.3 Wrapper
- PostgreSQL 17.11
- Spring Data JPA
- Flyway
- Spring Boot Actuator
- springdoc OpenAPI 2.9.1
- JUnit 5, Testcontainers 2.0.5, and WireMock 3.13.2
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

The application intentionally has no hard-coded database credential fallback. It fails fast when `DATABASE_URL`, `DATABASE_USERNAME`, or `DATABASE_PASSWORD` is missing. Official API credentials and endpoints are also supplied only through environment variables.

## Source ingestion

Each ingestion attempt follows one explicit lifecycle:

1. Persist a `RUNNING` `SourceRun` in its own transaction.
2. Call the configured official API outside that transaction.
3. Preserve every HTTP response body and its SHA-256 hash in a new `RawSnapshot` before interpreting the source result code.
4. Treat only Seoul `INFO-000` and FinLife `result.err_cd=000` as source-level success.
5. Mark the run `SUCCESS`, or persist a sanitized `UPSTREAM_ERROR` and mark it `FAILED`, in an independent transaction before returning or propagating an application exception.

An HTTP 2xx status alone is not sufficient for source execution success. The current adapters are `FSS_FINLIFE` for JSON and `SEOUL_POLICY_LOAN_RATE` for XML. Test fixtures are deterministic test inputs only and are never used as production fallback data.

Seoul ingestion uses a configurable page size of five by default. It reads `list_total_count` from the first valid response and requests only the remaining deterministic ranges. One logical `SourceRun` owns every page as a separate immutable `RawSnapshot`. The run succeeds only after every page succeeds; a failed page and every preceding page remain preserved, later pages are not requested, and no retry occurs.

## Phase 3 normalization

Normalization is explicitly separated from ingestion:

```text
FSS FinLife RawSnapshot -> InstitutionAlias -> FinancialInstitution
                       -> LoanProduct -> ProductTerm

Seoul dreamMoney RawSnapshot -> InstitutionAlias -> FinancialInstitution (nullable)
                            -> LoanRateObservation
```

FSS records are disclosed mortgage-product conditions and option rates. Seoul records are aggregated execution statistics for policy-linked commercial-bank financing. A Seoul `LoanRateObservation` is never linked to an FSS `LoanProduct`; the only shared dimension is an explicitly mapped institution.

FSS institution codes and names create or resolve canonical institutions by exact source identity. Seoul uses only seeded, explicitly confirmed code-and-name mappings. Unknown or uncertain Seoul identities are retained as `UNMAPPED`, their observations remain queryable, and their canonical institution is null. No fuzzy or approximate matching is performed.

Every `LoanProduct`, `ProductTerm`, and `LoanRateObservation` has a direct foreign key to the exact `RawSnapshot` that produced it. Snapshot-scoped source identities are protected by database unique constraints, and normalization uses deterministic lookup-before-insert behavior. Repeating normalization for the same successful run therefore creates no duplicates. One normalization invocation is transactional: malformed source data causes the entire invocation to roll back rather than partially commit.

## Manual official-API ingestion

Manual ingestion is disabled during normal startup and runs only with the `manual-ingestion` profile. Set the relevant variables in your uncommitted `.env` file, including the API key, base URL, and source-specific path or service name. Then run one source:

```bash
set -a
source .env
set +a
./gradlew bootRun --args='--spring.profiles.active=manual-ingestion --loanops.manual.source=FSS_FINLIFE'
```

For the Seoul source:

```bash
./gradlew bootRun --args='--spring.profiles.active=manual-ingestion --loanops.manual.source=SEOUL_POLICY_LOAN_RATE'
```

The profile uses a non-web application context, performs exactly one attempt, prints only the source code, run ID, status metadata, and payload hash, and then exits. It never prints API keys or authenticated request URLs. A failed attempt remains stored as `FAILED` and causes the command to fail. There is no automatic retry or scheduler.

## Manual normalization

Normalization is disabled during normal startup. After a successful ingestion, normalize that specific run:

```bash
set -a
source .env
set +a
./gradlew bootRun --args='--spring.profiles.active=manual-normalization --loanops.manual.run-id=<SOURCE_RUN_ID>'
```

The selected run must be `SUCCESS`. For Seoul, every snapshot in the paginated run is parsed and validated together. Failed runs, incomplete page sets, malformed records, and inconsistent totals are rejected without a partial normalized commit.

## Verification

Run the complete test suite. The integration tests start real PostgreSQL 17.11 containers, use WireMock for deterministic HTTP behavior, and do not use H2 or real API credentials:

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

Phase 3 includes source definitions, source execution records, paginated exact raw snapshots, explicit failure persistence, source-specific parsing, explicit institution aliases, FSS products and terms, Seoul rate observations, provenance, idempotent normalization, and profile-gated manual commands. It does not include fuzzy matching, data-quality scoring, quarantine, serving promotion or current tables, change detection, retries, scheduling, authentication, authorization, audit logs, frontend, or AI features.
