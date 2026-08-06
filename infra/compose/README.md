# Local Docker Compose — QualityOps Lab

Two compose files cover the full local stack.

| File | Purpose |
|---|---|
| `docker-compose.yml` | Infrastructure only: PostgreSQL, Redis, Kafka |
| `docker-compose.dev.yml` | App services: API + Gateway (built from source) |

> **All commands below must be run from the repository root**, not from this
> directory, so that build contexts and relative paths resolve correctly.

---

## Quick start

### Infrastructure only (run apps from your IDE)

```bash
docker compose -f infra/compose/docker-compose.yml up -d
```

### Full stack (infrastructure + API + Gateway in containers)

```bash
docker compose \
  -f infra/compose/docker-compose.yml \
  -f infra/compose/docker-compose.dev.yml \
  up -d --build
```

The `--build` flag re-builds images whenever source or Dockerfiles change.
Omit it on subsequent starts when source has not changed.

---

## Service URLs

| Service | URL | Notes |
|---|---|---|
| **Gateway** | http://localhost:8090 | Entry point for all API traffic |
| **API** | http://localhost:8080 | Direct access (bypass gateway) |
| **Swagger UI** | http://localhost:8080/swagger-ui.html | OpenAPI docs |
| **PostgreSQL** | localhost:5432 | Use a DB client or `psql` |
| **Redis** | localhost:6379 | |
| **Kafka (host)** | localhost:29092 | External listener for local tools |

---

## Local database credentials

| Setting | Value |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `qualityops` |
| Username | `qualityops` |
| Password | `qualityops` |
| JDBC URL | `jdbc:postgresql://localhost:5432/qualityops` |

> ⚠  **These are local development defaults only.**
> Never use them in staging or production environments.

---

## Seeded application login

After the API starts and Flyway migrations run, a demo owner account is
available:

| Field | Value |
|---|---|
| Email | `owner@demo.com` |
| Password | `password123` |

---

## Environment variable overrides

Credentials support environment interpolation. Copy `.env.example` to `.env`
at the repo root to override defaults:

```bash
cp .env.example .env
# edit .env as needed
```

`.env` is git-ignored and must never be committed.

---

## Health checks

Wait for all services to become healthy before sending traffic:

```bash
# Check status of all containers
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml ps

# Poll API health directly
curl http://localhost:8080/actuator/health

# Poll Gateway health
curl http://localhost:8090/actuator/health
```

Services follow this startup order enforced by `depends_on` + health checks:

```
postgres ──┐
redis    ──┼─► api ──► gateway
kafka    ──┘
```

---

## Logs

```bash
# Stream all logs
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml logs -f

# Stream a single service
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml logs -f api

docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml logs -f gateway
```

---

## Stop and clean up

```bash
# Stop all containers (data volumes preserved)
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml down

# Stop and remove volumes (⚠ destroys all local data)
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml down -v
```

---

## Validate compose configuration

```bash
docker compose \
  -f infra/compose/docker-compose.yml \
  -f infra/compose/docker-compose.dev.yml \
  config
```
