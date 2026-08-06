# QualityOps Worker

Async job runner. Consumes Kafka events and executes test runs.

## Responsibilities
- Consume `runs.requested` events from Kafka
- Execute test runs (simulated → Playwright → API → performance)
- Publish `results.chunk` events per test case
- Publish `runs.completed` or `runs.failed` events

## Run locally
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Requires Kafka to be running (see `infra/compose/docker-compose.yml`).
