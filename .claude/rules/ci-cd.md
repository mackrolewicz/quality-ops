---
paths:
  - "**/.github/**/*"
  - "**/workflows/**/*"
---
# CI/CD Pipeline Rules

- Cache Maven dependencies (`actions/setup-java` with `cache: "maven"`).
- Cache npm dependencies (`actions/setup-node` with `cache: "npm"`).
- Run backend and frontend jobs in parallel.
- Use matrix strategy for building multiple Docker images.
- NEVER hardcode secrets in workflow files. Use `${{ secrets.NAME }}`.
- GitHub Actions service containers for Postgres/Redis in tests.
- Every PR must pass: backend lint, backend test, frontend lint, frontend test, docker build.
- Use `actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`.
