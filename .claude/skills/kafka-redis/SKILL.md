---
name: kafka-redis
description: Use this skill when working with Kafka events or Redis caching/pub-sub. Covers event design, producer/consumer patterns, topic naming, serialization, caching strategies, and failure handling.
---

# Kafka + Redis patterns

This skill is the source of truth for event-driven and caching patterns
in this repo.

## Part 1: Apache Kafka

### 1. Topic naming convention

```
<domain>.<entity>.<action>
```

Examples:
```
runs.requested          # API → Worker: please execute this run
runs.started            # Worker → API: execution began
runs.completed          # Worker → API: execution finished
runs.failed             # Worker → API: execution failed
results.chunk           # Worker → API: single test case result
analysis.requested      # API → AI: analyze these failures
analysis.completed      # AI → API: analysis ready
```

### 2. Event design

Events are Java records. They are immutable, serializable, and self-contained.

```java
public record RunRequestedEvent(
    UUID runId,
    UUID projectId,
    UUID orgId,
    UUID suiteId,
    UUID environmentId,
    String triggeredBy,
    Instant requestedAt
) {}
```

**Rules:**
- Include `orgId` in every event for tenant isolation.
- Include a timestamp (`requestedAt`, `completedAt`, etc.).
- Include enough context for the consumer to process without calling back.
- Use UUIDs, not entity references — events cross service boundaries.
- Events are facts that already happened — name them in past tense or as
  commands when the consumer must act.

### 3. Producer pattern

```java
@Service
public class RunService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TestRun triggerRun(CreateRunRequest request, UUID orgId) {
        TestRun run = repository.save(buildRun(request, orgId));

        kafkaTemplate.send("runs.requested", run.getId().toString(),
            new RunRequestedEvent(
                run.getId(), run.getProjectId(), orgId,
                run.getSuiteId(), run.getEnvironmentId(),
                request.triggeredBy(), Instant.now()
            ));

        return run;
    }
}
```

**Key:** Use the entity ID as the Kafka message key — this ensures all events
for the same run go to the same partition (ordering guarantee).

### 4. Consumer pattern

```java
@Component
public class RunRequestedConsumer {
    private static final Logger log = LoggerFactory.getLogger(RunRequestedConsumer.class);

    @KafkaListener(topics = "runs.requested", groupId = "worker-runners")
    public void handle(RunRequestedEvent event) {
        log.info("Processing run: {}", event.runId());
        try {
            runner.execute(event);
        } catch (Exception e) {
            log.error("Run failed: {} — {}", event.runId(), e.getMessage());
            throw e;  // let Kafka retry or send to DLT
        }
    }
}
```

### 5. Serialization

Use JSON serialization with Spring Kafka's `JsonSerializer` / `JsonDeserializer`.

```yaml
spring:
  kafka:
    producer:
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.qualityops.*
```

### 6. Error handling and dead-letter topics

Configure a dead-letter topic (DLT) for messages that fail after retries:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
}
```

This retries 3 times with 1-second backoff, then sends to `<topic>.DLT`.

### 7. Idempotent consumers

Consumers **must** be idempotent — processing the same event twice should
produce the same result.

Pattern: check-then-act with a unique constraint:
```java
public void handle(RunRequestedEvent event) {
    if (runRepository.existsById(event.runId())) {
        log.warn("Duplicate event for run: {}", event.runId());
        return;
    }
    // process...
}
```

---

## Part 2: Redis

### 1. Key naming convention

```
<domain>:<entity>:<id>:<field>
```

Examples:
```
run:status:550e8400-...        # current status of a run
rate:api-token:abc123          # rate limit counter
session:user:550e8400-...      # user session data
cache:dashboard:org:550e8400   # cached dashboard data
```

### 2. What goes in Redis

| Use case | Key pattern | TTL | Notes |
|---|---|---|---|
| Run status (live) | `run:status:{runId}` | 1 hour | Updated every few seconds during execution |
| Rate limiting | `rate:{tokenId}` | 1 minute | Counter with sliding window |
| Session cache | `session:{userId}` | 30 minutes | Auth session, refreshed on activity |
| Dashboard cache | `cache:dashboard:{orgId}` | 30 seconds | Prevents hammering Postgres |

### 3. What does NOT go in Redis

- Anything that must survive a Redis restart (use Postgres).
- Test results (those are permanent records → Postgres).
- Large blobs (screenshots, logs → object storage or Postgres).

### 4. Spring Data Redis patterns

```java
@Service
public class RunStatusCache {
    private final StringRedisTemplate redis;
    private static final Duration RUN_STATUS_TTL = Duration.ofHours(1);

    public void updateStatus(UUID runId, String status) {
        redis.opsForValue().set(
            "run:status:" + runId,
            status,
            RUN_STATUS_TTL
        );
    }

    public Optional<String> getStatus(UUID runId) {
        String status = redis.opsForValue().get("run:status:" + runId);
        return Optional.ofNullable(status);
    }
}
```

### 5. Cache-aside pattern

For reads that are expensive but can tolerate slight staleness:

```java
public DashboardData getDashboard(UUID orgId) {
    String key = "cache:dashboard:" + orgId;
    DashboardData cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return cached;

    DashboardData fresh = analyticsService.computeDashboard(orgId);
    redisTemplate.opsForValue().set(key, fresh, Duration.ofSeconds(30));
    return fresh;
}
```

### 6. Rate limiting with Redis

```java
public boolean isRateLimited(String tokenId, int maxRequests) {
    String key = "rate:" + tokenId;
    Long count = redis.opsForValue().increment(key);
    if (count == 1) {
        redis.expire(key, Duration.ofMinutes(1));
    }
    return count > maxRequests;
}
```

### 7. Failure handling

Redis is **ephemeral**. If Redis is down:
- The app should still work (degrade gracefully).
- Skip cache reads (go straight to Postgres).
- Skip cache writes (don't fail the request).
- Log a warning, don't throw.

```java
try {
    return cache.getStatus(runId);
} catch (RedisConnectionException e) {
    log.warn("Redis unavailable, falling back to DB: {}", e.getMessage());
    return repository.findStatusById(runId);
}
```
