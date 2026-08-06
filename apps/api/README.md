# QualityOps API

Spring Boot 3 backend API. Modular monolith architecture.

## Modules
- `identity` — auth, users, roles, organizations
- `project` — projects, workspaces
- `environment` — environment registry, health tracking
- `testsuite` — test catalog: suites, cases, tags
- `execution` — run orchestration, scheduling
- `result` — results, analytics, flakiness scoring
- `testdata` — test data management
- `mock` — dependency virtualization
- `ai` — AI assistant integration

## Run locally
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Run tests
```bash
./mvnw test          # unit tests
./mvnw verify        # unit + integration tests (needs Docker)
```
