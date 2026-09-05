# QualityOps API

Spring Boot 3 backend API. Modular monolith architecture.

## Modules
- `identity` — auth, users, roles, organizations
- `project` — projects, workspaces
- `environment` — environment registry, health tracking
- `testsuite` — test catalog: suites, cases, tags (`secretRef` authoring since 2B3)
- `execution` — run orchestration; publishes `runs.requested`; sole writer of run state
- `result` — results, analytics, flakiness; consumes `runs.completed` + `results.chunk`
  (epoch-monotone upsert into `test_results` + `test_result_artifacts`); presigns
  read-only artifact GET URLs (`GET /api/v1/runs/{id}/artifacts`, `/api/v1/artifacts/{id}`)
- `testdata` — test data management
- `mock` — dependency virtualization
- `ai` — AI assistant integration

## Phase 2B3 (ADR-005)
The API stays the **sole writer** of authoritative run/result state. It gains a
`results.chunk` consumer (group `api-results`), the `test_result_artifacts` table
(`V11`), and a **read-only** MinIO client used only to presign short-TTL GET URLs —
it never writes to or proxies bytes from the object store. Set
`qualityops.artifacts.*` (`enabled`, `endpoint`, `bucket`, read-only
`access-key`/`secret-key`, `presign-ttl` — clamped to ≤ 900s).

## Run locally
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Run tests
```bash
./mvnw test          # unit tests
./mvnw verify        # unit + integration tests (needs Docker)
```
