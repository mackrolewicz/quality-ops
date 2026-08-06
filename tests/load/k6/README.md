# k6 load tests (playground)

Scripts will live here for smoke, API, and stress scenarios against the local
stack (gateway → API → Kafka).

**Not implemented yet** — see `docs/product/ROADMAP.md` → **Load testing with k6**.

## Prerequisites

- [k6 installed](https://k6.io/docs/get-started/installation/)
- Stack running: `docker compose -f infra/compose/docker-compose.yml up -d`
- API + gateway healthy

## Planned commands

```bash
k6 run tests/load/k6/smoke/health-and-login.js
k6 run --vus 50 --duration 2m tests/load/k6/api/trigger-run.js
```

Add scripts as you complete Phase 1+ and Phase 7 exercises.
