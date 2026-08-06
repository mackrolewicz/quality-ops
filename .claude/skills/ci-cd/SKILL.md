---
name: ci-cd
description: Use this skill when working with GitHub Actions CI/CD pipelines, build automation, container image publishing, and deployment workflows.
---

# CI/CD with GitHub Actions

This skill is the source of truth for how CI/CD pipelines work in this repo.

## 1. Pipeline structure

```
.github/workflows/
├── ci.yml              # runs on every PR and push to main
└── deploy.yml          # runs on merge to main (later phases)
```

## 2. CI pipeline (ci.yml)

Runs on every PR. Must pass before merge.

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  backend-lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
          cache: "maven"
      - run: cd apps/api && ./mvnw checkstyle:check

  backend-test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: qualityops_test
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        options: >-
          --health-cmd pg_isready
          --health-interval 5s
          --health-timeout 3s
          --health-retries 5
        ports:
          - 5432:5432
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: "temurin"
          cache: "maven"
      - run: cd apps/api && ./mvnw verify

  frontend-lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: "20"
          cache: "npm"
          cache-dependency-path: apps/web/package-lock.json
      - run: cd apps/web && npm ci
      - run: cd apps/web && npm run lint && npm run typecheck

  frontend-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: "20"
          cache: "npm"
          cache-dependency-path: apps/web/package-lock.json
      - run: cd apps/web && npm ci
      - run: cd apps/web && npm test

  docker-build:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        app: [api, worker, gateway, web]
    steps:
      - uses: actions/checkout@v4
      - run: docker build -f infra/docker/Dockerfile.${{ matrix.app }} -t qualityops/${{ matrix.app }}:ci apps/${{ matrix.app }}/
```

## 3. CI conventions

### Caching
- Always cache Maven dependencies (`actions/setup-java` with `cache: "maven"`).
- Always cache npm dependencies (`actions/setup-node` with `cache: "npm"`).
- This saves 1-3 minutes per run.

### Parallelism
- Backend and frontend jobs run in parallel.
- Use matrix strategy for building multiple Docker images.

### Service containers
- Use GitHub Actions service containers for Postgres, Redis in tests.
- No Kafka in CI (too heavy) — use Testcontainers for Kafka tests (they
  start their own containers).

### Secrets
- **Never** hardcode secrets in workflow files.
- Use GitHub repository secrets: `${{ secrets.SOME_SECRET }}`.
- Required secrets (to configure before deployment):
  - `AZURE_CREDENTIALS` — for AKS deployment
  - `CONTAINER_REGISTRY_URL` — ACR URL
  - `CONTAINER_REGISTRY_USERNAME` / `PASSWORD`

## 4. Deploy pipeline (deploy.yml) — later phases

```yaml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        app: [api, worker, gateway, web]
    steps:
      - uses: actions/checkout@v4
      - uses: azure/login@v2
        with:
          creds: ${{ secrets.AZURE_CREDENTIALS }}
      - run: |
          az acr login --name ${{ secrets.CONTAINER_REGISTRY_URL }}
          docker build -f infra/docker/Dockerfile.${{ matrix.app }} \
            -t ${{ secrets.CONTAINER_REGISTRY_URL }}/qualityops/${{ matrix.app }}:${{ github.sha }} \
            apps/${{ matrix.app }}/
          docker push ${{ secrets.CONTAINER_REGISTRY_URL }}/qualityops/${{ matrix.app }}:${{ github.sha }}

  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/aks-set-context@v4
        with:
          cluster-name: qualityops-aks
          resource-group: qualityops-rg
      - run: |
          helm upgrade --install qualityops infra/helm/qualityops/ \
            -f infra/helm/qualityops/values-prod.yaml \
            --set image.tag=${{ github.sha }}
```

## 5. PR quality gates

Before a PR can merge:

| Check | Required? | Notes |
|---|---|---|
| Backend lint | Yes | Checkstyle |
| Backend tests | Yes | JUnit + Testcontainers |
| Frontend lint | Yes | ESLint + TypeScript |
| Frontend tests | Yes | Vitest |
| Docker builds | Yes | All images must build |
| E2E tests | No (later) | Playwright after infra is stable |
| Security scan | No (later) | Trivy container scan |

## 6. Branch protection rules (configure in GitHub)

- Require PR reviews (1 reviewer minimum).
- Require status checks to pass.
- Require branches to be up to date before merging.
- No force push to `main`.
- No direct commits to `main`.

## 7. Deployment progression

```
Local (Docker Compose)
  ↓ merge to main
Staging (Azure Container Apps or AKS)
  ↓ manual approval
Production (AKS)
```

## 8. Quick commands

```bash
# Run CI checks locally before pushing
cd apps/api && ./mvnw verify
cd apps/web && npm run lint && npm run typecheck && npm test

# Build all Docker images locally
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml build

# Validate GitHub Actions workflow syntax
act -l  # requires `act` CLI: https://github.com/nektos/act
```
