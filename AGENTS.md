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
- Commit the initial `RUNNING` execution before making the external request, and commit `SUCCESS` or `FAILED` independently so an exception cannot erase the execution record.
- Do not equate HTTP transport success with source success. Preserve the raw body first, then validate the source-specific result code.
- Store only sanitized operational error messages. Never persist or log API keys or authenticated request URLs.
- A manual retry creates a new execution linked to the original failed execution; it never mutates the original record.
- Keep collection failure independent from deletion or replacement of serving data.

## Testing

- Cover important behavior with automated tests.
- Use PostgreSQL Testcontainers for database integration tests; do not substitute H2.
- Use deterministic tests and explicit assertions for data integrity, provenance, failure handling, and serving isolation when those capabilities are introduced.
- Keep test fixtures and fault-injection data out of production data tables.

## Phase 4 boundary

Phase 4 contains the Phase 1–3 capabilities plus deterministic quality validation, persisted quality issues, quarantine references, final publication results, serving pointers, last-valid-serving protection, atomic promotion, deterministic change records, and profile-gated manual publication.

- Keep FSS product conditions and Seoul aggregate execution statistics semantically separate. Never link a Seoul observation directly to an FSS product.
- Resolve institutions only by exact source identity or an explicit confirmed cross-source mapping. Retain uncertain source identities and records as `UNMAPPED`.
- Every product, term, and observation must reference its exact raw snapshot.
- Treat one normalization invocation as an atomic unit and reject incomplete or malformed source data without partial writes.
- Keep ingestion success, normalization, quality acceptance, and publication as separate lifecycle concepts.
- Persist quality evidence before rejecting publication. Quarantine references invalid normalized history; it never deletes or duplicates it.
- Promote a selected source run as one atomic unit. Any quality issue blocks every serving pointer from that run and preserves the last valid serving state.
- Validate FSS term rates as individually non-negative with `minimum_rate <= maximum_rate`; do not assume its optional average is bounded by those values. Keep Seoul's `minimum <= average <= maximum` rule.
- Record the quality policy version used for every final publication decision and never recalculate historical decisions in place.
- Compare explicit business fields only. Provenance-only changes produce `NO_CHANGE` and must not rewrite serving pointers.
- Do not infer removal when complete-snapshot absence semantics are uncertain.
- Do not add retries, scheduling, authentication, authorization, operator audit, overrides, issue workflows, notifications, frontend, or AI features until a later phase explicitly approves them.

## Phase 5 boundary

Phase 5 exposes the verified lifecycle through read-only APIs and a minimal React operations console.

- Keep every `/api/v1` endpoint read-only and do not expose workflow mutations.
- Map database data into dedicated response DTOs; never serialize JPA entities or lazy proxies.
- Never expose API keys, authenticated source URLs, or complete raw payloads.
- Read current product and observation data through serving pointers rather than historical normalized tables.
- Preserve deterministic ordering and bounded pagination for operational history.
- Describe Seoul data as policy-linked commercial-bank loan execution statistics, not generic market rates.
- Keep local CORS origins explicit and never enable wildcard production CORS.
- Do not add authentication, retries, scheduling, notifications, distributed infrastructure, or AI features in this phase.
