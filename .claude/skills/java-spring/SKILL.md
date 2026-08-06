---
name: java-spring
description: Use this skill when writing or reviewing Java / Spring Boot code. Covers module structure, dependency injection, JPA entities, REST controllers, service patterns, exception handling, and project-specific conventions.
---

# Java + Spring Boot patterns

This skill is the source of truth for how Java code is written in this repo.

## 1. Module structure (hexagonal architecture)

This project uses **hexagonal architecture** (ports and adapters). See
`ARCHITECTURE.md` for the full explanation. In practice, there are two
levels of structure depending on module complexity.

### Full hexagonal layout (for complex modules)

Use this when the module has multiple infrastructure concerns (Kafka +
Redis + JPA) or complex domain logic. Examples: `execution`, `result`.

```
<module>/
├── adapter/
│   ├── in/
│   │   ├── web/
│   │   │   └── <Module>Controller.java       # REST driving adapter
│   │   └── messaging/
│   │       └── <Event>Consumer.java          # Kafka driving adapter
│   └── out/
│       ├── persistence/
│       │   ├── <Module>JpaRepository.java    # JPA driven adapter
│       │   └── <Entity>Entity.java           # JPA entity (infra layer)
│       ├── messaging/
│       │   └── <Module>KafkaPublisher.java   # Kafka driven adapter
│       └── cache/
│           └── <Module>RedisCache.java       # Redis driven adapter
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── <UseCase>UseCase.java         # input port (interface)
│   │   └── out/
│   │       ├── <Module>Repository.java       # output port (interface)
│   │       ├── <Module>EventPublisher.java   # output port (interface)
│   │       └── <Module>Cache.java            # output port (interface)
│   └── service/
│       └── <Module>Service.java              # implements input ports
├── domain/
│   ├── <DomainEntity>.java                   # pure Java domain entity
│   ├── <ValueObject>.java                    # enums, value objects
│   └── <DomainPolicy>.java                   # business rules
├── dto/
│   ├── Create<Entity>Request.java
│   └── <Entity>Response.java
└── exception/
    └── <Entity>NotFoundException.java
```

**Dependency direction (non-negotiable):**
```
adapters → application (ports) → domain
              ↑ never ↓
```

- Domain has ZERO dependencies on Spring, JPA, Kafka, or Redis.
- Application layer depends on domain + port interfaces only.
- Adapters implement port interfaces and depend on frameworks.

### Simplified layout (for simple CRUD modules)

Use this for modules that are mostly CRUD without complex domain logic.
Examples: `project`, `environment`, `testsuite`.

```
<module>/
├── <Module>Controller.java     # @RestController — HTTP endpoints only
├── <Module>Service.java        # @Service — all business logic lives here
├── <Module>Repository.java     # extends JpaRepository — data access only
├── dto/
│   ├── Create<Entity>Request.java   # @Valid annotated input record
│   └── <Entity>Response.java        # output record (never expose entities)
├── model/
│   └── <Entity>.java                # @Entity JPA class
├── event/
│   └── <Entity>CreatedEvent.java    # Kafka event record
├── mapper/
│   └── <Entity>Mapper.java          # entity ↔ DTO conversion
└── exception/
    └── <Entity>NotFoundException.java
```

**Rule:** Start with the simple layout. Upgrade to full hexagonal when you
find yourself needing multiple adapters or the domain logic becomes complex
enough to warrant isolation.

### Hard rules (both layouts)
- Controllers call services, never repositories directly.
- Services call repositories. Services can call other services (via ports in hexagonal).
- Repositories never call services (no circular dependencies).
- DTOs (records) cross module boundaries, not entities.
- Domain entities are never exposed to the API layer.

## 2. Dependency injection

**Constructor injection only.** Never `@Autowired` on fields.

```java
@Service
public class ProjectService {
    private final ProjectRepository repository;
    private final KafkaTemplate<String, Object> kafka;

    public ProjectService(ProjectRepository repository,
                          KafkaTemplate<String, Object> kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }
}
```

For single-constructor classes, Spring auto-injects — no `@Autowired` needed.

## 3. DTOs as records

All request/response objects are Java records. Never expose JPA entities
in API responses.

```java
public record CreateProjectRequest(
    @NotBlank String name,
    @Size(max = 500) String description
) {}

public record ProjectResponse(
    UUID id,
    String name,
    String description,
    Instant createdAt
) {}
```

## 4. JPA entities

Entities are mutable classes (not records) because JPA needs setters and
a no-arg constructor.

```java
@Entity
@Table(name = "projects")
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    // getters, setters — or use Lombok @Getter @Setter if team agrees
}
```

**Multi-tenancy rule:** Every entity has `orgId` (or inherits it through a
parent relationship). Every repository query must filter by `orgId`.

## 5. REST controllers

```java
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal UserPrincipal user) {
        return service.listByOrg(user.orgId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request,
                                  @AuthenticationPrincipal UserPrincipal user) {
        return service.create(request, user.orgId());
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable UUID id,
                               @AuthenticationPrincipal UserPrincipal user) {
        return service.getByIdAndOrg(id, user.orgId());
    }
}
```

**Conventions:**
- Use `@Valid` on request bodies.
- Always pass `orgId` from the authenticated user — never trust the client.
- Return records, not entities.
- Use `@ResponseStatus` for non-200 responses.

## 6. Exception handling

Use a global `@ControllerAdvice` for consistent error responses:

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(EntityNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }
}
```

In business logic, throw specific exceptions:
```java
throw new ProjectNotFoundException("Project not found: " + id);
```

Never: `catch (Exception e) { ... }` in business logic.

## 7. Logging

Use SLF4J via `@Slf4j` (Lombok) or manual declaration:

```java
private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
```

Log at the right level:
- `log.error(...)` — something broke and needs attention.
- `log.warn(...)` — unexpected but recoverable.
- `log.info(...)` — business events (run started, user created).
- `log.debug(...)` — technical details for troubleshooting.

Never log secrets, tokens, passwords, or full request bodies in production.

## 8. Testing patterns

See the `testing` skill for full details. Quick reference:

- Unit tests: plain JUnit 5 + Mockito. Test services, not controllers.
- Integration tests: `@SpringBootTest` + Testcontainers.
- API tests: `@WebMvcTest` + `MockMvc`.
- Test class naming: `<Class>Test.java` for unit, `<Class>IT.java` for integration.

## 9. Configuration

Use `application.yml` with Spring profiles:

```yaml
# application.yml — shared defaults
spring:
  application:
    name: qualityops-api

# application-local.yml — local dev overrides
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/qualityops

# application-prod.yml — production (values from env vars)
spring:
  datasource:
    url: ${DATABASE_URL}
```

Never hardcode credentials. Use `${ENV_VAR}` placeholders in config files.

## 10. Security configuration

### Spring Security setup

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.csrfTokenRepository(
                CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .cors(cors -> cors.configurationSource(corsConfig()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/api/**").authenticated()
            )
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())))
            .build();
    }
}
```

### Method-level authorization

```java
@PreAuthorize("hasRole('ADMIN') or hasRole('OWNER')")
@PostMapping
public ProjectResponse create(...) { ... }

@PreAuthorize("hasAnyRole('MEMBER', 'ADMIN', 'OWNER')")
@GetMapping
public List<ProjectResponse> list(...) { ... }
```

See the `security` skill for full details on OAuth, SSO, TLS, rate limiting,
CORS, and OWASP compliance.

## 11. Hexagonal ports and adapters in practice

### Input port (use case interface)

```java
public interface TriggerRunUseCase {
    RunResponse trigger(CreateRunRequest request, UUID orgId);
}
```

### Output port (repository interface in application layer)

```java
public interface RunRepository {
    TestRun save(TestRun run);
    Optional<TestRun> findByIdAndOrgId(UUID id, UUID orgId);
    List<TestRun> findByProjectId(UUID projectId);
}

public interface RunEventPublisher {
    void publishRunRequested(RunRequestedEvent event);
}
```

### Service implements input ports, depends on output ports

```java
@Service
public class RunService implements TriggerRunUseCase {
    private final RunRepository repository;
    private final RunEventPublisher eventPublisher;

    public RunService(RunRepository repository, RunEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public RunResponse trigger(CreateRunRequest request, UUID orgId) {
        var run = TestRun.create(request, orgId);
        repository.save(run);
        eventPublisher.publishRunRequested(run.toEvent());
        return RunResponse.from(run);
    }
}
```

### Driven adapter implements output port

```java
@Repository
public class RunJpaAdapter implements RunRepository {
    private final SpringDataRunRepository jpa;

    @Override
    public TestRun save(TestRun run) {
        var entity = RunEntity.fromDomain(run);
        var saved = jpa.save(entity);
        return saved.toDomain();
    }
}
```

The key insight: `RunService` has no idea whether data is stored in Postgres,
MongoDB, or an in-memory map. It depends on the `RunRepository` interface,
not the implementation. This makes it trivially testable.

## 12. Event-driven patterns in services

### Publishing events after state changes

```java
@Service
public class RunService {
    private final RunRepository repository;
    private final RunEventPublisher events;

    @Transactional
    public RunResponse trigger(CreateRunRequest request, UUID orgId) {
        var run = TestRun.create(request, orgId);
        repository.save(run);
        events.publishRunRequested(new RunRequestedEvent(
            run.getId(), run.getProjectId(), orgId,
            run.getSuiteId(), run.getEnvironmentId(),
            request.triggeredBy(), Instant.now()
        ));
        return RunResponse.from(run);
    }
}
```

**Rule:** Save to DB first, then publish event. If the event publish fails,
the DB transaction still commits. Use the transactional outbox pattern later
if exactly-once is critical.

### Consuming events

```java
@Component
public class RunCompletedConsumer {
    private final ResultService resultService;

    @KafkaListener(topics = "runs.completed", groupId = "api-results")
    public void handle(RunCompletedEvent event) {
        resultService.finalizeRun(event.runId(), event.orgId());
    }
}
```
