# Local Development Setup

How to get the QualityOps Lab running on your machine.

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java JDK | 21+ | `java -version` |
| Node.js | 20+ | `node --version` |
| npm | 10+ | `npm --version` |
| Docker Desktop | Latest | `docker --version` |
| Git | Latest | `git --version` |

## Step 1: Clone the repo

```bash
git clone <repo-url>
cd qualityops-lab
```

## Step 2: Start infrastructure

```bash
docker compose -f infra/compose/docker-compose.yml up -d
```

This starts:
- PostgreSQL on port 5432
- Redis on port 6379
- Kafka on port 9092

Verify everything is healthy:
```bash
docker compose -f infra/compose/docker-compose.yml ps
```

All services should show "healthy" status.

## Step 3: Start the backend API

```bash
cd apps/api
./mvnw spring-boot:run
```

The API starts on http://localhost:8080.
Health check: http://localhost:8080/actuator/health

## Step 4: Start the worker

In a new terminal:
```bash
cd apps/worker
./mvnw spring-boot:run
```

The worker connects to Kafka and starts consuming events.

## Step 5: Start the gateway

In a new terminal:
```bash
cd apps/gateway
./mvnw spring-boot:run
```

The gateway starts on http://localhost:8090 and routes to the API.

## Step 6: Start the frontend

In a new terminal:
```bash
cd apps/web
npm install
npm run dev
```

The frontend starts on http://localhost:5173.

## Step 7: Verify

Open http://localhost:5173 in your browser. You should see the
QualityOps Lab dashboard.

## Troubleshooting

| Problem | Solution |
|---|---|
| Port already in use | Check `docker ps` and `lsof -i :PORT` |
| Kafka won't start | Ensure Docker has enough memory (4GB+) |
| API can't connect to Postgres | Check docker compose is running and healthy |
| Frontend can't reach API | Check gateway is running on port 8090 |
| Maven download hangs | Check your network / proxy settings |

## Stopping everything

```bash
# Stop apps: Ctrl+C in each terminal

# Stop infrastructure
docker compose -f infra/compose/docker-compose.yml down

# Stop and delete all data (fresh start)
docker compose -f infra/compose/docker-compose.yml down -v
```
