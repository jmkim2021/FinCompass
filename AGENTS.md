# LoanOps Engineering Rules

## Scope and simplicity

- Implement only the currently approved phase.
- Prefer the simplest implementation that satisfies the requirements.
- Do not introduce speculative abstractions or unnecessary infrastructure.
- Do not add Kafka, Redis, Kubernetes, microservices, vector databases, RAG, LLM features, or similar technologies unless a later approved phase explicitly requires them.

## Financial data integrity

- Production financial data must come only from configured official external APIs.
- Never generate or use fake financial data as a production fallback.
- Preserve every external API response as an immutable raw snapshot before normalization.
- Every normalized record must retain provenance that traces it to its source execution and original raw data.
- Invalid, stale, quarantined, or failed incoming data must never replace the last valid serving data.
- Keep semantically different financial datasets separate, even when they contain the same institution names.
- Never automatically fuzzy-match financial institutions. Preserve source names and use explicit mappings; uncertain values remain unmapped.

## Failure handling and operations

- Persist external API failures and make them observable.
- Do not silently catch, suppress, or discard exceptions.
- Keep failed source executions as failed records with their diagnostic context.
- A manual retry creates a new execution linked to the original failed execution; it never mutates the original record.
- Keep collection failure independent from deletion or replacement of serving data.

## Testing

- Cover important behavior with automated tests.
- Use PostgreSQL Testcontainers for database integration tests; do not substitute H2.
- Use deterministic tests and explicit assertions for data integrity, provenance, failure handling, and serving isolation when those capabilities are introduced.
- Keep test fixtures and fault-injection data out of production data tables.

## Phase 1 boundary

Phase 1 contains only the executable Spring Boot foundation, PostgreSQL and Flyway configuration, Actuator health, OpenAPI/Swagger, Docker Compose, and PostgreSQL integration testing. Do not add LoanOps business entities, external API adapters, quality rules, quarantine, retry, security, audit, frontend, or AI features in this phase.
