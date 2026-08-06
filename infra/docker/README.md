# Dockerfiles

Multi-stage Dockerfiles for each application.

| File | App | Base image |
|---|---|---|
| `Dockerfile.api` | Spring Boot API | eclipse-temurin:21 |
| `Dockerfile.worker` | Kafka worker | eclipse-temurin:21 |
| `Dockerfile.gateway` | Spring Cloud Gateway | eclipse-temurin:21 |
| `Dockerfile.web` | React frontend | node:20 → nginx |

All images use multi-stage builds and run as non-root users.
