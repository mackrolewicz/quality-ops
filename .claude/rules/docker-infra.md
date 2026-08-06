---
paths:
  - "**/Dockerfile*"
  - "**/docker-compose*.yml"
  - "**/docker-compose*.yaml"
  - "infra/**/*"
  - "**/k8s/**/*"
  - "**/helm/**/*"
---
# Docker & Infrastructure Rules

- Multi-stage builds for all Dockerfiles (build stage + runtime stage).
- Run as non-root user in production images. Always add `USER app`.
- Always include HEALTHCHECK in Dockerfiles.
- Never bake secrets or `.env` files into images.
- Use specific base image tags, never `latest`.
- Docker Compose: always use health checks with `depends_on: condition: service_healthy`.
- Named volumes for persistent data (Postgres, Kafka).
- Kubernetes: always set resource requests AND limits.
- Kubernetes: always configure liveness AND readiness probes.
- ConfigMaps for non-sensitive config, Secrets for credentials.
- Helm: run `helm lint` and `helm template` before committing.
