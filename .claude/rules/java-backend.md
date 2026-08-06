---
paths:
  - "**/*.java"
---
# Java Backend Rules

- Constructor injection only. Never `@Autowired` on fields.
- Records for DTOs. JPA entities are mutable classes.
- Narrow exceptions: never `catch (Exception e)` in business logic.
- SLF4J logging only. No `System.out.println`.
- Every entity and query must filter by `orgId` for multi-tenancy.
- `@Valid` on all `@RequestBody` parameters.
- `@PreAuthorize` on all controller methods (except health checks).
- Return DTOs from controllers, never JPA entities.
- Use Java 21 features: records, sealed interfaces, pattern matching.
- Follow hexagonal architecture for complex modules (execution, result).
  Dependency direction: adapters → application (ports) → domain.
- Simple CRUD modules can use flat structure (controller → service → repo).
- Flyway migrations are append-only. Never edit an applied migration.
