# QualityOps Gateway

Spring Cloud Gateway — single entry point for all client requests.

## Responsibilities
- Route requests to the API backend
- Handle CORS
- Rate limiting per API token
- Add tracing headers (OpenTelemetry)
- Serve static frontend assets (production)

## Run locally
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Runs on port 8090 by default.
