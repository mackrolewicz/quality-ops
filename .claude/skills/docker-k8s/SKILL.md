---
name: docker-k8s
description: Use this skill when working with Docker, Docker Compose, Kubernetes, or Helm. Covers Dockerfile patterns, Compose configuration, K8s manifests, Helm charts, and local development setup.
---

# Docker + Kubernetes patterns

This skill is the source of truth for containerization and orchestration
in this repo.

## 1. Dockerfile patterns

### Spring Boot apps (API, Worker, Gateway)

Multi-stage build:

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### React frontend

```dockerfile
# Build stage
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runtime stage
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY infra/docker/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget -qO- http://localhost:80/ || exit 1
```

### Hard rules
- Always use multi-stage builds.
- Always run as non-root user in production.
- Always include a HEALTHCHECK.
- Never copy secrets or `.env` files into images.
- Use specific base image tags (not `latest`).
- Order COPY commands from least to most frequently changing (for cache).

## 2. Docker Compose (local development)

```yaml
# infra/compose/docker-compose.yml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: qualityops
      POSTGRES_USER: qualityops
      POSTGRES_PASSWORD: localdev
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U qualityops"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      CLUSTER_ID: local-dev-cluster-id-001
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres-data:
```

### Dev overrides

```yaml
# infra/compose/docker-compose.dev.yml
services:
  api:
    build:
      context: ../../apps/api
      dockerfile: ../../infra/docker/Dockerfile.api
    ports:
      - "8080:8080"
      - "5005:5005"  # debug port
    environment:
      SPRING_PROFILES_ACTIVE: local
      JAVA_TOOL_OPTIONS: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
```

### Conventions
- Always use health checks with `depends_on: condition: service_healthy`.
- Use named volumes for data that should persist across restarts.
- Use `.env` file for passwords, never hardcode in compose files.
- Separate base infra (compose.yml) from app services (compose.dev.yml).

## 3. Kubernetes (later phases)

### Deployment pattern

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: qualityops-api
spec:
  replicas: 2
  selector:
    matchLabels:
      app: qualityops-api
  template:
    spec:
      containers:
        - name: api
          image: qualityops/api:latest
          ports:
            - containerPort: 8080
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
          envFrom:
            - configMapRef:
                name: api-config
            - secretRef:
                name: api-secrets
```

### K8s conventions
- Always set resource requests and limits.
- Always configure liveness and readiness probes.
- Use ConfigMaps for non-sensitive config, Secrets for credentials.
- Use namespaces to isolate environments.
- Label everything consistently: `app`, `version`, `environment`.

## 4. Helm charts (production)

```
infra/helm/qualityops/
├── Chart.yaml
├── values.yaml
├── values-staging.yaml
├── values-prod.yaml
└── templates/
    ├── api-deployment.yaml
    ├── api-service.yaml
    ├── worker-deployment.yaml
    ├── gateway-deployment.yaml
    ├── ingress.yaml
    └── configmap.yaml
```

### Helm conventions
- One chart for the whole platform (umbrella chart pattern).
- Environment-specific values files: `values-staging.yaml`, `values-prod.yaml`.
- Use `{{ .Release.Name }}` in resource names to support multiple installs.
- Always run `helm lint` and `helm template` before committing.

## 5. Quick commands reference

```bash
# Start local infra
docker compose -f infra/compose/docker-compose.yml up -d

# Start everything including apps
docker compose -f infra/compose/docker-compose.yml \
               -f infra/compose/docker-compose.dev.yml up -d

# View logs
docker compose logs -f api
docker compose logs -f worker

# Reset everything
docker compose down -v   # -v removes volumes (data loss!)

# Build a specific image
docker build -f infra/docker/Dockerfile.api -t qualityops/api apps/api/

# K8s dry run
kubectl apply -f infra/k8s/ --dry-run=client

# Helm template check
helm template qualityops infra/helm/qualityops/ -f infra/helm/qualityops/values-staging.yaml
```
