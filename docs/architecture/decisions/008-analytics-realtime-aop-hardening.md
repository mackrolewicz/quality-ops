# ADR-008: Analytics, real-time run updates, AOP cross-cutting concerns, and security hardening (Phase 2E)

## Status

Proposed.

- **Realises** `docs/product/PHASE-2-PLAN.md` §2E and the Phase-2E rows of `docs/product/ROADMAP.md` (flaky detection, duration trends, environment health, Redis dashboard cache, WebSocket run updates, application-level rate limiting, Spring AOP `@Audited`/`@Timed`, HTTPS in staging, CI dependency/image scanning).
- **Preserves** every invariant carried from ADR-001…007: multi-tenancy on every row / query / event; the API is the **sole writer** of authoritative state; hexagonal architecture for complex modules; Flyway append-only (this increment starts at **V19**, current max is V18); every new **table** carries `org_id NOT NULL`; boring, reversible technology; `apps/web` stays contract-compatible (additive only — no frontend work in 2E).
- **Does not touch** `packages/shared-events` (no new event record, no `SCHEMA_VERSION` bump), **adds no Kafka topic**, and **changes nothing in `apps/worker`**. The WebSocket push hooks off the *existing* `api-execution` (`runs.started|completed|failed`) and `api-results` (`results.chunk`) consumers via a new outbound port. Environment-health probes run **in the API** on the existing ShedLock `@Scheduled` infrastructure (ADR-006).
- **Extends** ADR-006 §2 (a fifth leader-elected `@Scheduled` job, `environment-health-probe`), ADR-006 §6 / ADR-007 §10 (new Micrometer meters), ADR-007 §4 (the structured `com.qualityops.api.audit` log line becomes a durable `audit_log` row when `@Audited` is applied), and ADR-007 §5.4 / gateway config (a `/ws/**` passthrough route, no rate limiter).
- **Three new dependencies**, each justified in §10: `spring-boot-starter-aop`, `spring-boot-starter-websocket`, `spring-boot-starter-cache` (all first-party Spring Boot starters). One new build-time plugin: `org.owasp:dependency-check-maven` (in a `security-scan` profile).

## Context

After Phase 2D the platform can trigger, schedule, queue, dispatch, execute (real HTTP + browser), retry, reap, and webhook-notify runs. `test_results` (V7, `uq_test_results_run_case`, `attempt_epoch` since V11) and `test_result_artifacts` (V11) hold the authoritative per-case outcome. `environments` (V4) carries an admin-controlled `status` PG enum (`ACTIVE`/`INACTIVE`) and a `base_url`. Analytics today is a single project pass/fail summary (`ResultController GET /api/v1/projects/{projectId}/analytics`, `ResultService.getAnalytics`). There is **no** flaky detection, **no** duration analytics, **no** environment health monitoring, **no** dashboard read cache (Spring Data Redis is on the classpath but only for `host`/`port` — no `RedisTemplate` bean, no `@EnableCaching`), **no** server push (the dashboard polls), **no** application-level rate limiting (only the gateway's per-IP `RequestRateLimiter`), **no** AOP (`spring-boot-starter-aop` is absent), and **no** dependency/image scanning in `.github/workflows/ci.yml` (jobs: `web`, `backend`, `backend-it`).

Phase 2E adds these nine capabilities. Two design constraints shape every choice:

1. **Read-side, additive, reversible.** Every 2E feature is a read path (analytics, health view, WS push), a transparent optimisation (cache), an edge guard (rate limit), a cross-cutting observer (AOP), or CI/config (HTTPS, scanning). Nothing changes the write model of runs or results. The only new authoritative state is the environment-health history and the audit trail — both API-written, both `org_id`-scoped.
2. **The API must not become an SSRF vector or a memory hog.** The environment-health probe makes outbound HTTP from the API to tenant-supplied URLs — it reuses the private-address denylist pattern (ADR-007 §6.2 `WebhookUrlValidator`), extracted into a shared guard, and only probes `STAGING`/`PRODUCTION` environments. The WebSocket broker is the in-memory simple broker with hard send-buffer / send-time limits so a slow client cannot grow the heap.

---

## Decision

### 1. Flaky detection — on-the-fly windowed query, no materialised table

**Decision: compute the stability/flakiness score per `test_case_id` on demand with one native Postgres window query over `test_results`, cached in Redis (§4, 30 s). Do NOT add a materialised `test_case_stats` table.**

Rationale over the alternative: a materialised table updated on every `runs.completed` needs an incremental-maintenance path (a classic drift-bug surface), a backfill migration, and its own `org_id`/indexes — for zero added expressiveness at the lab's data volume. The 30 s Redis cache already removes the per-request cost. If profiling later shows the window query is hot, `test_case_stats` is a clean follow-up (shape noted in *Alternatives*). **V19 is therefore an index migration, not a stats table.**

**Score definition** (deterministic; unit-tested):
- Take the last `window` `test_results` rows per `test_case_id` (default `window = 20`), `status IN ('PASSED','FAILED')` only (SKIPPED/FLAKY ignored), ordered by `test_results.created_at`, scoped to `org_id` + the run's `project_id`.
- `runsAnalyzed = r` (require `r >= minRuns`, default 5, else the case is omitted).
- `transitions = t` = count of consecutive chronological pairs whose status differs (`LAG(status)`).
- `flakinessScore = r <= 1 ? 0.0 : round2(t / (r - 1.0))` — **0.0 for all-pass or all-fail; →1.0 for perfect alternation.**
- `stabilityScore = round2(1.0 - flakinessScore)`.

**Query** (native, `AnalyticsRepositoryAdapter`, `NamedParameterJdbcTemplate`):

```sql
WITH ranked AS (
  SELECT r.test_case_id, r.status, r.created_at,
         ROW_NUMBER() OVER (PARTITION BY r.test_case_id ORDER BY r.created_at DESC) rn
  FROM test_results r
  JOIN test_runs run ON run.id = r.run_id
  WHERE r.org_id = :orgId AND run.project_id = :projectId
    AND r.status IN ('PASSED','FAILED')
),
win AS (SELECT * FROM ranked WHERE rn <= :window),
seq AS (
  SELECT test_case_id, status, created_at,
         LAG(status) OVER (PARTITION BY test_case_id ORDER BY created_at) prev
  FROM win
)
SELECT test_case_id,
       COUNT(*)                                              AS runs_analyzed,
       COUNT(*) FILTER (WHERE status = 'PASSED')             AS pass_count,
       COUNT(*) FILTER (WHERE prev IS NOT NULL AND prev <> status) AS transitions,
       MAX(created_at)                                       AS last_run_at
FROM seq
GROUP BY test_case_id
HAVING COUNT(*) >= :minRuns
ORDER BY transitions DESC, runs_analyzed DESC
```

`testCaseName` and `lastStatus` are joined/derived in a second cheap lookup (`test_cases`, and the `rn = 1` row).

**Endpoint** — `GET /api/v1/analytics/flaky?projectId={uuid}&window={int}` · RBAC `hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')` · `orgId` from `UserPrincipal` · `400 VALIDATION_ERROR` if `projectId` missing · `404` (via `GetProjectUseCase.getDomain`) if the project is not in the caller's org · `window` clamped `[5, 50]`.

**Files** — `apps/api/src/main/java/com/qualityops/api/result/adapter/in/web/AnalyticsController.java`; `.../result/application/port/in/{GetFlakyAnalyticsUseCase,GetDurationTrendsUseCase,GetSlowTestsUseCase}.java`; `.../result/application/service/AnalyticsService.java`; `.../result/application/port/out/AnalyticsRepository.java`; `.../result/adapter/out/persistence/AnalyticsRepositoryAdapter.java`; `.../result/dto/{FlakyAnalyticsResponse,FlakyTestRow,DurationTrendsResponse,TrendPoint,SlowTestsResponse,SlowTestRow}.java`.

**Key tests** — `AnalyticsFlakyScoreTest` (unit, pure): alternating P/F/P/F ⇒ `flakiness ≈ 1.0`; all-pass ⇒ `0.0`; all-fail ⇒ `0.0`; single transition in 20 ⇒ `≈ 0.05`; `< minRuns` ⇒ omitted. `AnalyticsRepositoryIT` (Testcontainers): seed two orgs × two cases, assert the window truncates at `window`, `HAVING minRuns` filters, and **org B's results never appear in org A's report**.

---

### 2. Duration trends & slowest tests — two native aggregates over `test_results.duration_ms` + `test_runs.created_at`

**Decision: two read endpoints backed by grouped native queries on the existing columns; no new stored data.** `test_runs` has `created_at` (V6); `test_results` has `duration_ms` + `created_at` (V7). Postgres `percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)` gives p95 without an approximation library.

**`GET /api/v1/analytics/trends?projectId={uuid}&days={int}`** (default 7, clamp `[1, 90]`) → `DurationTrendsResponse { projectId, days, points: [ { date, totalRuns, passedRuns, failedRuns, avgDurationMs, p95DurationMs } ] }`, one point per `date_trunc('day', created_at)` in the window. Run counts come from `test_runs` grouped by day + status; duration aggregates from `test_results` joined to `test_runs` grouped by run-day. Missing days are zero-filled in Java.

**`GET /api/v1/analytics/slow?projectId={uuid}&days={int}&limit={int}`** (default `days=7`, `limit=20`, clamp `limit` `[1, 100]`) → `SlowTestsResponse { projectId, days, limit, tests: [ { testCaseId, testCaseName, samples, avgMs, p95Ms, maxMs } ] }`, top-`limit` `test_case_id` by `p95Ms DESC` over the window, `samples >= 3` required.

Both: RBAC `hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')`, `orgId` from principal, `projectId` required + org-checked, cached in Redis (§4).

**Files** — same `AnalyticsController` / `AnalyticsService` / `AnalyticsRepository` as §1.

**Key tests** — `AnalyticsTrendsIT`: seed runs across 3 days, assert 3 populated points + zero-filled gaps, avg/p95 arithmetic against hand-computed values, org isolation. `AnalyticsSlowIT`: 5 cases with known durations ⇒ correct top-3 by p95, `samples < 3` excluded.

---

### 3. Environment health monitoring — a ShedLock `@Scheduled` probe in the API, new health columns + history table, STAGING/PRODUCTION only

**Decision: a fifth leader-elected `@Scheduled` job (`environment-health-probe`) on the ADR-006 infrastructure; add `health_status` + probe bookkeeping columns to `environments` (a `VARCHAR + CHECK`, NOT touching the existing `environment_status` PG enum) and an `environment_health_check` history table; probe only `type IN ('STAGING','PRODUCTION')` environments; reuse an extracted private-address guard.**

Deviation from the plan text called out: PHASE-2-PLAN §2E says "`environments.status` transitions". The existing `environments.status` is a PG enum (`ACTIVE`/`INACTIVE`) that is the **admin lifecycle** flag. Conflating operational health into it would need a risky `ALTER TYPE … ADD VALUE` (not transaction-safe, hard to reverse) and would destroy the admin/health distinction. We add **`environments.health_status VARCHAR(16)`** instead (`UNKNOWN|HEALTHY|DEGRADED|DOWN`, `VARCHAR + CHECK` per ADR-006 §3.1 reasoning).

**Why STAGING/PRODUCTION only:** `DEV` `base_url`s are routinely `http://localhost:*` — exactly what the SSRF denylist must refuse. A staging/prod URL that resolves to a private/link-local/metadata address is a genuine SSRF and is refused: the probe records `health_status = UNKNOWN`, `error_detail = 'blocked:disallowed-target'`, and never opens a socket. A self-hosted lab can flip `qualityops.scheduling.environment-health.allow-private-targets=true` (default `false`, documented).

**The job** — `com.qualityops.api.environment.application.scheduler.EnvironmentHealthProbeJob`: `@Component`, `@ConditionalOnProperty("qualityops.scheduling.jobs-enabled", matchIfMissing = true)`, `@Scheduled(fixedDelayString = "${qualityops.scheduling.environment-health.interval:PT60S}")`, `@SchedulerLock(name = "environment-health-probe", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")`. Sets `metrics.leaderHeld("environment-health-probe", true/false)` in a `finally`, wraps the body in the `qualityops.environment.probe_duration` timer, delegates to `EnvironmentHealthService.sweep()`. `sweep()` is public (called directly by ITs, like `StuckRunReaperService.sweep()`).

**`EnvironmentHealthService.sweep()`** — selects a batch (`batch-size`, default 50) of `environments` where `status = 'ACTIVE' AND deleted_at IS NULL AND type IN ('STAGING','PRODUCTION') AND (last_probe_at IS NULL OR last_probe_at < now() - :probeInterval)` (`probe-interval` default `PT5M`) via `FOR UPDATE SKIP LOCKED`. Per environment, in its own `TransactionTemplate` unit:
1. `OutboundAddressGuard.check(baseUrl)` — throws ⇒ record `UNKNOWN`/`blocked:disallowed-target`, `consecutive_failures` unchanged.
2. `EnvironmentHealthProbe.probe(baseUrl)` (out-port; `HttpEnvironmentHealthProbe` adapter — JDK `HttpClient`, `followRedirects(NEVER)`, connect + request timeout `probe-timeout` default `PT5S`, `GET` with `HEAD` fallback, reads only the status line + up to 4 KiB, no body retained) ⇒ `ProbeResult(reachable, httpStatus, latencyMs, error)`.
3. Classify: `2xx/3xx` ⇒ `HEALTHY` (`consecutive_failures = 0`, `last_healthy_at = now()`); otherwise `consecutive_failures += 1`, then `DEGRADED` if `consecutive_failures >= degraded-after` (default 1) and `< failure-threshold`, `DOWN` if `>= failure-threshold` (default 3).
4. `EnvironmentHealthRepository.recordProbe(...)` — one guarded `UPDATE environments SET health_status=?, last_probe_at=now(), last_healthy_at=?, consecutive_failures=? WHERE id=? AND org_id=?` **plus** `INSERT environment_health_check(...)`. API stays sole writer.
5. On a `health_status` change, `metrics.environmentHealthTransition(newStatus)`.

**Endpoint** — `GET /api/v1/environments/{id}/health` · RBAC `hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')` · `orgId` from principal (`404` if the env is not in the caller's org) → `EnvironmentHealthResponse { environmentId, healthStatus, lastProbeAt, lastHealthyAt, consecutiveFailures, recentChecks: [ { checkedAt, healthStatus, httpStatus, latencyMs, error } ] }` (last 20 checks). Flat path, matching the existing `/api/v1/environments/{id}` GET/PUT/DELETE precedent.

**Retention** — `QueueMaintenanceService.prune()` gains a fifth delete: `environment_health_check` older than `qualityops.scheduling.environment-health.history-retention` (default `P14D`).

**Shared guard** — extract ADR-007's `WebhookUrlValidator` private-address logic into `com.qualityops.api.common.net.OutboundAddressGuard` (`check(String url, boolean allowHttp, boolean allowPrivate)`), and re-point `WebhookUrlValidator` at it (https-only, private denied). The environment probe calls it with `allowHttp = true`, `allowPrivate = qualityops.scheduling.environment-health.allow-private-targets`. Residual DNS-rebind risk documented (identical to ADR-003 §5 / ADR-007 §Risks — accepted; IP-pinned fetch is a later hardening).

**Files** — `apps/api/src/main/java/com/qualityops/api/environment/`: `application/scheduler/EnvironmentHealthProbeJob.java`, `application/service/EnvironmentHealthService.java`, `application/port/in/{ProbeEnvironmentsUseCase,GetEnvironmentHealthUseCase}.java`, `application/port/out/{EnvironmentHealthProbe,EnvironmentHealthRepository}.java`, `adapter/out/probe/HttpEnvironmentHealthProbe.java`, `adapter/out/persistence/{EnvironmentHealthCheckEntity,EnvironmentHealthCheckJpaRepository,EnvironmentHealthRepositoryAdapter}.java`, `adapter/in/web/EnvironmentHealthController.java`, `domain/EnvironmentHealthStatus.java`, `dto/{EnvironmentHealthResponse,EnvironmentHealthCheckView}.java`; `apps/api/src/main/java/com/qualityops/api/common/net/OutboundAddressGuard.java`; `apps/api/src/main/java/com/qualityops/api/config/EnvironmentHealthProperties.java`.

**Key tests** — `EnvironmentHealthClassificationTest` (unit): 1 failure ⇒ `DEGRADED`; 3 ⇒ `DOWN`; success resets to `HEALTHY` + `consecutive_failures = 0`. `OutboundAddressGuardTest`: `http://10.0.0.5`, `https://169.254.169.254`, `http://[::1]`, `http://127.0.0.1` rejected; `https://staging.example.com` accepted; `allowPrivate = true` lets `10.x` through. `EnvironmentHealthProbeIT` (Testcontainers + MockWebServer): 200 ⇒ `HEALTHY` row + `last_healthy_at`; MockWebServer 503 ×3 ⇒ `DOWN`; `type = DEV` env is **never selected**; a foreign-org env is untouched when the probe runs; `sweep()` twice is idempotent (respects `probe-interval`).

---

### 4. Redis dashboard cache — Spring Cache over Redis, 30 s TTL, per-org key prefix, fail-open, evict on `runs.completed`

**Decision: `spring-boot-starter-cache` + `@EnableCaching` + a `RedisCacheManager` with a 30 s default TTL and a per-cache key prefix that embeds `orgId`; `@Cacheable` on the analytics read methods and the run-list read; a `CacheErrorHandler` that logs and falls through to the database on any Redis failure; eviction driven from `RunLifecycleService` (the existing `api-execution` consumer) after a terminal transition moved a row.**

Rationale over alternatives: a hand-rolled cache-aside in each service duplicates serialization + TTL + null-handling logic and is easy to get wrong on tenancy; Spring Cache with an explicit key expression (`#orgId + ':' + …`) makes the tenant scoping visible and reviewable. Caffeine (in-process) is rejected because the API runs multiple replicas and the plan explicitly calls for Redis.

**Caches** (all 30 s TTL): `analytics.flaky`, `analytics.trends`, `analytics.slow`, `runs.list`. Keys: `#orgId + ':' + #projectId + ':' + <params>` for analytics; `#orgId + ':' + <filters> + ':' + #page + ':' + #size` for the list. `RedisCacheConfig` calls `.computePrefixWith(name -> name + "::")` so a stored key is `analytics.flaky::{orgId}:{projectId}:{window}` — tenant-partitioned by construction. GenericJackson2JsonRedisSerializer for values.

**Invalidation** — `RunLifecycleService.onRunCompleted` / `onRunFailed`, after `moved == true` (and after the retry/webhook hooks), calls `DashboardCacheInvalidator.evictForOrg(orgId)` (a plain bean — cross-bean call, proxy-safe). It `SCAN`s and deletes `analytics.*::{orgId}:*` and `runs.list::{orgId}:*` via `StringRedisTemplate`. Per-org (not per-project) because the terminal event's payload is not guaranteed to carry `projectId`, a cross-project evict within one org is cheap, and the 30 s TTL bounds staleness anyway. Eviction failure is caught + logged (`qualityops.cache.errors`); entries expire on their own.

**Fail-open** — `CachingConfigurer#errorHandler()` returns a `LoggingCacheErrorHandler`: `handleCacheGetError` / `PutError` / `EvictError` / `ClearError` all log at WARN, increment `qualityops.cache.errors`, and **return normally** so the `@Cacheable` method body runs against Postgres. A Redis outage degrades latency, never correctness.

**Files** — `apps/api/src/main/java/com/qualityops/api/config/{RedisCacheConfig,CacheProperties}.java`; `apps/api/src/main/java/com/qualityops/api/result/application/service/DashboardCacheInvalidator.java`. `@Cacheable` annotations added on `AnalyticsService` methods and `RunService.list` (the `TriggerRunUseCase`/`GetRunUseCase` paths stay uncached).

**Key tests** — `DashboardCacheIT` (Testcontainers Redis + Postgres): first `GET /api/v1/analytics/flaky` hits the DB, second is served from Redis (assert via a spy on `AnalyticsRepository` or a Micrometer cache-miss count); a `runs.completed` for the org evicts and the next read hits the DB again; **org B's cache entry is untouched** when org A's run completes. `CacheFailOpenIT`: point the cache at a dead Redis port ⇒ the endpoint still returns 200 from the DB and `qualityops.cache.errors` increments.

---

### 5. WebSocket `/ws/runs/{id}` — STOMP over SockJS, simple broker, Redis pub/sub fan-out, JWT on `CONNECT`, org-checked `SUBSCRIBE`

**Decision: `spring-boot-starter-websocket`, a STOMP endpoint at `/ws` (SockJS-enabled), the in-memory simple broker on `/topic`, and a Redis pub/sub bridge so every replica re-broadcasts lifecycle updates to its own local sessions. The scope's `/ws/runs/{id}` is realised as a STOMP subscription to `/topic/runs/{runId}`.** Push frames are emitted from the existing `RunLifecycleConsumer` / `ResultChunkConsumer` handlers through a new outbound port — **no new Kafka topic, no new consumer group**.

Rationale over alternatives: raw `WebSocketHandler` still needs the same starter and gives no subscription multiplexing or fallback; **Server-Sent Events** (no new dependency, one-way, `EventSource` auto-reconnect) is a genuine lighter option and is recorded as the fallback, but the roadmap and PHASE-2-PLAN explicitly specify WebSocket and reference `WebSocketStompClient` for the test, so STOMP/SockJS is chosen. A real STOMP broker relay (RabbitMQ) is rejected as a heavyweight new infrastructure dependency for a lab; the **simple broker + Redis bridge** pattern needs only the already-present `spring-data-redis`.

**Config** — `com.qualityops.api.realtime.config.WebSocketConfig` (`@EnableWebSocketMessageBroker`):
- `registerStompEndpoints`: `addEndpoint("/ws").setAllowedOriginPatterns(<qualityops.ws.allowed-origins>).withSockJS()`.
- `configureMessageBroker`: `enableSimpleBroker("/topic")`, `setApplicationDestinationPrefixes("/app")`.
- `configureWebSocketTransport`: `setSendTimeLimit(10_000)`, `setSendBufferSizeLimit(512 * 1024)`, `setMessageSizeLimit(64 * 1024)` — **backpressure guard**: a client that cannot keep up is disconnected rather than buffered unbounded.
- `configureClientInboundChannel`: register `StompAuthChannelInterceptor`.

**Auth** — the HTTP handshake for `/ws/**` is `permitAll` in `SecurityConfig` (SockJS `/info` must be reachable); no data flows until an authenticated STOMP `CONNECT`. `StompAuthChannelInterceptor`:
- `CONNECT`: read the `Authorization: Bearer …` STOMP header, validate via `JwtService`, set the resulting `UserPrincipal` as the session user; reject (`ERROR` frame) on a missing/invalid token.
- `SUBSCRIBE` to `/topic/runs/{runId}`: parse `runId`, call `GetRunUseCase.get(runId, principal.orgId())`; on `RunNotFoundException` (wrong org or unknown) deny the subscription. **This is the tenant isolation boundary for the socket.**

**Fan-out** — `com.qualityops.api.realtime.adapter.out.StompRunProgressNotifier implements com.qualityops.api.execution.application.port.out.RunProgressNotifier` (port defined in `execution`, dependency inward). `RunLifecycleService` (after each guarded transition) and `ResultService.recordChunk` call `runProgressNotifier.publish(RunProgressEvent)` — **best-effort, wrapped in try/catch, never fails the Kafka consumer**. `StompRunProgressNotifier.publish` writes the event JSON to the Redis channel `qualityops:ws:runs` via `StringRedisTemplate`. `RedisRunEventBridge` (a `RedisMessageListenerContainer` `@Bean` subscribed to that channel, one per replica) receives every such message — local and remote — and calls `SimpMessagingTemplate.convertAndSend("/topic/runs/" + runId, event)` to its own sessions. A Redis publish failure is caught: the bridge degrades to **local-only** broadcast (the originating replica still notifies its own sessions; clients on other replicas rely on reconnect / the dashboard's existing poll).

**Payload** — `RunProgressEvent { runId, orgId, type: "STATUS"|"CASE", status, queueState, casesTotal, casesDone, testCaseId, verdict, at }` — lightweight status/progress only; **never** full result or artifact bodies.

**Gateway** — `apps/gateway/src/main/resources/application.yml` gains a dedicated `ws-route` (`predicates: Path=/ws/**`, `uri: ${API_URL}`, **no `RequestRateLimiter` filter** — long-lived upgrades must not burn rate tokens). Spring Cloud Gateway proxies the `Upgrade` automatically.

**Files** — `apps/api/src/main/java/com/qualityops/api/realtime/`: `config/{WebSocketConfig,StompAuthChannelInterceptor,WebSocketProperties}.java`, `adapter/out/{StompRunProgressNotifier,RedisRunEventBridge}.java`, `dto/RunProgressEvent.java`; `apps/api/src/main/java/com/qualityops/api/execution/application/port/out/RunProgressNotifier.java`. `SecurityConfig` adds `.requestMatchers("/ws/**").permitAll()`.

**Key tests** — `RunProgressWebSocketIT` (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebSocketStompClient` + Testcontainers Postgres/Kafka/Redis): connect with a valid JWT, `SUBSCRIBE /topic/runs/{id}`, drive the run to `runs.completed`, assert a `STATUS` frame with `status = PASSED` arrives within a timeout. `WebSocketAuthIT`: `CONNECT` without a token ⇒ `ERROR`; `SUBSCRIBE` to another org's run ⇒ denied, no frames. `RedisBridgeIT`: publish to `qualityops:ws:runs` directly ⇒ a subscribed client receives it (proves cross-replica fan-out). `RunProgressNotifierTest`: a Redis publish exception does not propagate out of `RunLifecycleService.onRunCompleted`.

---

### 6. Application-level rate limiting — `@RateLimited` + a `HandlerInterceptor` (NOT an aspect), Redis fixed-window counters per `org_id` per operation, `429` + `Retry-After`

**Decision: a `@RateLimited(operation, limit, window)` annotation read by a Spring MVC `HandlerInterceptor` on the controller methods that front the run-enqueue and CI paths — not an AOP aspect.** Redis holds one fixed-window `INCR` counter per `(orgId, operation, windowIndex)` via a tiny Lua script. Over-limit ⇒ `429` with `Retry-After` and `X-RateLimit-*` headers. **Fail-open** on Redis error.

Rationale over the aspect option (explicitly offered by the scope): (a) an interceptor runs *before* the controller body, so it can cleanly short-circuit with a `429` envelope and set response headers — a service-layer `@Around` cannot set response headers without awkward request-scoped plumbing; (b) it is **immune to the AOP self-invocation limitation** (§7) — there is no proxy to bypass; (c) `orgId` is already on the authenticated `UserPrincipal` at the controller. This is a deliberate split from §7: audit/timing are cross-cutting on *arbitrary* service beans (aspect), rate limiting is an *edge* concern (interceptor). This is distinct from the gateway's per-IP `RequestRateLimiter` (ADR decision #10) — that is per-client transport protection; this is per-tenant per-operation fairness.

**Placement** — `@RateLimited(operation = "run.trigger", limit = "${qualityops.ratelimit.run-trigger.limit:60}", window = "${qualityops.ratelimit.run-trigger.window:PT1H}")` on `RunController.trigger`; `@RateLimited(operation = "ci.run", limit = "${qualityops.ratelimit.ci-run.limit:120}", window = "PT1H")` on `CiRunController.submit`. The run-enqueue path (`RunService.trigger` → `EnqueueRunUseCase`) and the CI path (`CiRunService.submit`) are both reached only through these two controller methods, so the interceptor gates them exactly as the scope requires.

**Counter** — key `ratelimit:{orgId}:{operation}:{epochSecond / windowSeconds}`; Lua: `local c = redis.call('INCR', KEYS[1]); if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end; return c`. `c > limit` ⇒ reject; `Retry-After` = remaining PTTL in seconds. Headers on **every** response from a `@RateLimited` handler: `X-RateLimit-Limit`, `X-RateLimit-Remaining` (`max(0, limit - c)`), `X-RateLimit-Reset` (epoch seconds of the next window). Fixed-window burst-at-boundary (up to 2× near the edge) is documented and accepted for a lab; a sliding-window refinement is a follow-up.

**Fail-open** — a `RedisConnectionFailureException` / timeout in the interceptor is caught, logged at WARN, `qualityops.ratelimit.errors` incremented, and the request **allowed** (the gateway per-IP ceiling still applies). Availability over strictness, stated.

**Error surface** — `RateLimitExceededException extends RuntimeException`; `GlobalExceptionHandler` gains `@ExceptionHandler(RateLimitExceededException.class) @ResponseStatus(TOO_MANY_REQUESTS)` returning `ApiResponse.error("RATE_LIMITED", "…")` and the `Retry-After` header (the interceptor sets it before the exception propagates, or the handler re-derives it). `apps/web` may now receive a `429` from `POST /api/v1/runs` — a standard status code, not a contract break; noted for the frontend.

**Files** — `apps/api/src/main/java/com/qualityops/api/common/ratelimit/`: `RateLimited.java` (annotation), `RateLimitInterceptor.java`, `RedisRateLimiter.java` (Lua wrapper), `RateLimitProperties.java`, `RateLimitExceededException.java`; a `WebMvcConfigurer` registration in `apps/api/src/main/java/com/qualityops/api/config/WebMvcConfig.java`. `GlobalExceptionHandler` extended.

**Key tests** — `RedisRateLimiterTest` (Testcontainers Redis): the `limit + 1`-th call in a window returns over-limit; the counter expires after `window`. `RunTriggerRateLimitIT` (`@SpringBootTest`): with `limit = 2`, the 3rd `POST /api/v1/runs` in the window ⇒ `429` + `Retry-After` + `X-RateLimit-Remaining: 0`; **org B is unaffected** (independent counter). `RateLimitFailOpenIT`: dead Redis ⇒ requests still succeed, `qualityops.ratelimit.errors` increments. `CiRunRateLimitIT`: the CI path is gated independently of `run.trigger`.

---

### 7. Spring AOP — `@Audited` (durable `audit_log` rows) + `@Timed` (slow-op Micrometer metrics), and the self-invocation limitation

**Decision: `spring-boot-starter-aop`; two custom annotations `@Audited` and `@Timed` in a new flat `com.qualityops.api.audit` module with `@Around` aspects; `@Audited` writes an `audit_log` row (V21) via an application service (the aspect never touches a repository directly); the project stance on self-invocation is documented and enforced by a review rule plus an explicit test.**

Deviation from PHASE-2-PLAN's `common/audit/` wording: audit has a table, a repository, and a service, so it is a **module** (`com.qualityops.api.audit`) like every other persisted capability in this codebase, not a `common/` helper. The annotations still read naturally (`@Audited`, `@Timed`).

**`@Audited(action, targetType)`** — `@Around`: on normal return, `AuditRecorder.record(action, targetType, targetId, actorUserId, outcome = SUCCESS, detail)`; on a thrown exception, `record(… outcome = FAILURE, detail = exception class + message)` then **rethrow unchanged**. `AuditRecorder` runs the insert in `Propagation.REQUIRES_NEW` and swallows+logs its own failures — so an audit-write problem never breaks the business call, and an audit row survives a later business rollback. **Stated tradeoff:** an action whose transaction later rolls back can still leave a `SUCCESS` audit row; acceptable for a lab, noted. `actorUserId` is read from the `SecurityContext` (nullable for system callers — reaper, probe). Applied first to the ADR-007 §4 `org.run_concurrency.update` site (promoting its structured log line to a row) and to `EnvironmentService` mutations, `ProjectService`/`TestSuiteService` deletes, `WebhookEndpointService`.

**`@Timed(value, slowThresholdMillis = 1000)`** — `@Around`: records `registry.timer("qualityops.slow_op", "op", value)`; if elapsed `> slowThresholdMillis`, increments `qualityops.slow_op.exceeded{op}` and logs WARN. Named `Timed` in `com.qualityops.api.audit.annotation`; we deliberately do **not** import or wire Micrometer's `io.micrometer.core.annotation.Timed`/`TimedAspect` — a self-contained annotation keeps the slow-op semantics (threshold + WARN) explicit and avoids the classpath ambiguity.

**Ordering** — `TimingAspect` `@Order(0)` (outermost — total wall time, including audit I/O), `AuditAspect` `@Order(10)` (inner). The §6 `RateLimitInterceptor` runs before any aspect (MVC interceptor precedes the controller), so a rate-limited request is never audited or timed.

**Self-invocation limitation — the project's stance (mandatory documentation):**
- Spring AOP proxies intercept calls that **enter a bean from outside** (another bean, or the container). A call from one method of a bean to another method of the **same instance** (`this.other()`) does **not** pass through the proxy, so `@Audited` / `@Timed` on that inner method are **silently ignored**.
- **Stance:** annotate only the **outermost service entry point** that a controller (or another bean) invokes. Never place `@Audited`/`@Timed` on a `private` method or on a method only ever called internally. Do **not** use `AopContext.currentProxy()` (it couples business code to the proxy and needs `exposeProxy = true`) — instead, if an inner step must be audited, extract it into a **separate bean**.
- Enforced by: a review checklist item in `.claude/rules/java-backend.md`, and an explicit test `AopSelfInvocationTest` that calls an annotated method **through** the proxy (asserts the aspect fired) and **directly via `this`** from a sibling method (asserts the aspect did **not** fire) — the test *documents* the limitation as executable behaviour.
- The **§6 `@RateLimited` interceptor is not proxy-based and therefore not subject to this** — one of the reasons it is an interceptor. Micrometer's own `@Timed`/`TimedAspect`, had we used it, would share the limitation.

**Files** — `apps/api/src/main/java/com/qualityops/api/audit/`: `annotation/{Audited,Timed}.java`, `aspect/{AuditAspect,TimingAspect}.java`, `application/{AuditRecorder}.java`, `application/port/out/AuditLogRepository.java`, `adapter/out/persistence/{AuditLogEntity,AuditLogJpaRepository,AuditLogRepositoryAdapter}.java`, `domain/{AuditAction,AuditOutcome}.java`; `apps/api/src/main/java/com/qualityops/api/config/AopConfig.java` (`@EnableAspectJAutoProxy` — belt-and-braces; Boot auto-configures it).

**Key tests** — `AuditAspectTest` (`@SpringBootTest`, mocked `AuditRecorder`): annotated method returns ⇒ one `SUCCESS` record with the right `action`/`targetId`; throws ⇒ one `FAILURE` record **and** the original exception propagates. `TimingAspectTest`: fast call ⇒ timer recorded, no `exceeded`; a call over the threshold ⇒ `qualityops.slow_op.exceeded{op}` incremented + WARN. `AopOrderingTest`: with both annotations, timing wraps audit (verify via captured timestamps / a spy). `AopSelfInvocationTest` (as above). `AuditLogRepositoryIT`: a row is written with `org_id` set and is invisible to a different-org query.

---

### 8. HTTPS in staging — document LB/ingress termination as the norm; ship an opt-in Spring Boot `server.ssl.*` staging profile on the gateway with placeholder key material by reference

**Decision: config + docs only (no k8s/Helm — that is Phase 5).** The recommended production/staging path is TLS termination at the load balancer / ingress (Azure Application Gateway or nginx-ingress), with the gateway staying HTTP on the pod network. For "no LB available" staging and local-staging parity, `apps/gateway` gains an `application-staging.yml` that enables `server.ssl.*` from **environment variables only** — no keystore is committed (that would be a secret).

HSTS is already emitted by the gateway (`spring.cloud.gateway.filter.secure-headers.strict-transport-security: max-age=31536000; includeSubDomains`, verified in `apps/gateway/src/main/resources/application.yml`) — unchanged.

```yaml
# apps/gateway/src/main/resources/application-staging.yml
server:
  port: ${SERVER_PORT:8443}
  ssl:
    enabled: ${GATEWAY_TLS_ENABLED:true}
    key-store: ${GATEWAY_TLS_KEYSTORE:file:/etc/qualityops/tls/keystore.p12}
    key-store-type: PKCS12
    key-store-password: ${GATEWAY_TLS_KEYSTORE_PASSWORD:}
    key-alias: ${GATEWAY_TLS_KEY_ALIAS:qualityops-gateway}
```

A deployment that terminates TLS upstream sets `GATEWAY_TLS_ENABLED=false` and keeps port 8090. `.env.example` gains `GATEWAY_TLS_ENABLED`, `GATEWAY_TLS_KEYSTORE`, `GATEWAY_TLS_KEYSTORE_PASSWORD`, `GATEWAY_TLS_KEY_ALIAS`. New runbook `docs/runbooks/https-staging.md`: generate a PKCS12 keystore (`keytool` / `mkcert` for local staging), mount it, set the vars; or terminate at ingress and leave the profile off. No Dockerfile change required (the profile is inert unless `SPRING_PROFILES_ACTIVE=staging`).

**Files** — `apps/gateway/src/main/resources/application-staging.yml`; `docs/runbooks/https-staging.md`; `.env.example` (edit).

**Key tests** — none automated (config/docs only); the runbook includes a manual `curl -vk https://localhost:8443/actuator/health` check and a `openssl s_client` HSTS-header verification step. The existing gateway ITs run with the profile **off** and are unaffected.

---

### 9. CI scanning — OWASP Dependency-Check (Maven), `npm audit --audit-level=high`, Trivy image scan; fail on high/critical; suppression files with justification

**Decision: a new parallel `security-scan` GitHub Actions job for Java dependency scanning + image scanning, plus `npm audit` folded into the existing `web` job. Fail the build on CVSS ≥ 7 / `HIGH`,`CRITICAL`. Suppressions live in committed, `CODEOWNERS`-guarded files and must carry a justification + review date.**

**Maven** — `pom.xml` (parent) gains `org.owasp:dependency-check-maven` in a `security-scan` `<profile>` (not the default build, to keep local `mvn verify` fast):

```xml
<configuration>
  <failBuildOnCVSS>7</failBuildOnCVSS>
  <formats><format>HTML</format><format>SARIF</format></formats>
  <suppressionFiles>
    <suppressionFile>${maven.multiModuleProjectDirectory}/.github/dependency-check-suppressions.xml</suppressionFile>
  </suppressionFiles>
  <nvdApiKeyEnvironmentVariable>NVD_API_KEY</nvdApiKeyEnvironmentVariable>
</configuration>
```

**`security-scan` job** — `setup-java` (Temurin 21, `cache: maven`) + cache `~/.m2/repository/org/owasp/dependency-check-data` (the NVD database — avoids the multi-minute cold download and the NVD rate-limit flakiness); `NVD_API_KEY: ${{ secrets.NVD_API_KEY }}`; run `mvn -B -ntp -DskipTests -Psecurity-scan verify`. Then build the API/worker/gateway images and run `aquasecurity/trivy-action` with `severity: HIGH,CRITICAL`, `exit-code: 1`, `ignore-unfixed: true`, `trivyignores: .trivyignore`. SARIF uploaded to the code-scanning tab.

**`web` job** — add `npm audit --audit-level=high --omit=dev` after `npm ci` (production dependency tree only; dev-only advisories are tracked, non-blocking). Forced transitive upgrades go in `apps/web/package.json` `overrides`.

**Suppression governance** — `.github/dependency-check-suppressions.xml` (`<suppress until="YYYY-MM-DDZ">` + a comment: CVE, why it does not apply, linked issue, review date) and `.trivyignore` (`CVE-… # reason # review-by:YYYY-MM-DD`). `.github/CODEOWNERS` requires review on both files. **No `|| true`, no blanket `--audit-level=critical` downgrade** — a real advisory is either fixed or explicitly, reviewably suppressed.

**False-positive / flakiness handling (documented Risk):** the NVD feed occasionally rate-limits or mis-attributes a CVE to a shaded artifact. Mitigations: the cached NVD DB + API key remove the rate-limit source; a mis-attribution is handled by a **time-boxed** suppression entry (never permanent) with a linked issue; the job is `continue-on-error: false` but a documented "re-run after NVD recovers" note is in the runbook.

**Exit-criteria test (planted vulnerability):** `docs/runbooks/security-scanning.md` documents (and a throwaway PR demonstrates) adding `commons-collections:commons-collections:3.2.1` (CVE-2015-6420, CVSS 7.5) to `apps/api/pom.xml` ⇒ the `security-scan` job **fails** on `failBuildOnCVSS=7` ⇒ removing it returns the job to green. This is the ROADMAP 2E exit check "CI fails on a planted vulnerable dependency".

**Files** — `.github/workflows/ci.yml` (edit: new `security-scan` job, `npm audit` in `web`); `pom.xml` (edit: `security-scan` profile); `.github/dependency-check-suppressions.xml` (new, empty-with-schema); `.trivyignore` (new, empty-with-comment); `.github/CODEOWNERS` (edit); `docs/runbooks/security-scanning.md` (new); `apps/web/package.json` (`overrides` block if needed).

**Key tests** — the CI jobs themselves run on the 2E PR (green). The planted-vuln check is a one-off documented in the runbook (not a permanent job). `npm audit` and Trivy exit codes are asserted implicitly by the pipeline.

---

## Consolidated summary

### New migrations V19–V21 (append-only, in order)

| File | Purpose | `org_id` placement | `SchemaMigrationIT` assertions to add |
|---|---|---|---|
| `V19__add_test_results_analytics_indexes.sql` | `CREATE INDEX idx_test_results_case_created ON test_results (test_case_id, created_at DESC)`; `idx_test_results_run_created ON test_results (run_id, created_at)`; `idx_test_results_org_created ON test_results (org_id, created_at DESC)`. Supports the §1–§2 window/aggregate queries. | n/a (no new table) | `pg_indexes` for `test_results` contains the three new names |
| `V20__create_environment_health.sql` | `ALTER TABLE environments ADD COLUMN health_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (health_status IN ('UNKNOWN','HEALTHY','DEGRADED','DOWN'))`, `last_probe_at TIMESTAMPTZ`, `last_healthy_at TIMESTAMPTZ`, `consecutive_failures INT NOT NULL DEFAULT 0`. `CREATE TABLE environment_health_check (id UUID PK DEFAULT gen_random_uuid(), org_id UUID NOT NULL, environment_id UUID NOT NULL REFERENCES environments(id), project_id UUID NOT NULL, checked_at TIMESTAMPTZ NOT NULL, health_status VARCHAR(16) NOT NULL CHECK (…), http_status INT, latency_ms INT, error_detail VARCHAR(500), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`; `idx_env_health_check_env (environment_id, checked_at DESC)`, `idx_env_health_check_org (org_id)`. | `environment_health_check.org_id NOT NULL` — new table; carries `org_id` for the tenant-scoped GET and the retention sweep without a join. `health_status` is `VARCHAR + CHECK`, **not** a PG enum (the `environment_status` enum stays admin-only). | `environments` has `health_status`/`consecutive_failures` (`is_nullable=NO`, default present); `environment_health_check` exists with `org_id is_nullable=NO`; the two indexes exist; `health_status data_type = character varying` |
| `V21__create_audit_log.sql` | `CREATE TABLE audit_log (id UUID PK DEFAULT gen_random_uuid(), org_id UUID NOT NULL, actor_user_id UUID, action VARCHAR(64) NOT NULL, target_type VARCHAR(64), target_id UUID, outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS','FAILURE')), detail JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`; `idx_audit_log_org_created (org_id, created_at DESC)`, `idx_audit_log_action (action)`. | `org_id NOT NULL` — new table; audit entries are tenant-scoped; the Phase-4 `GET /api/v1/admin/audit` filters by it. `actor_user_id` nullable for system actions (reaper, probe). `outcome` is `VARCHAR + CHECK`, not a PG enum. | `audit_log` exists with `org_id is_nullable=NO`; both indexes exist; `outcome data_type = character varying` |

`flywayHistory_afterMigration_containsVersions1Through18` → **`…Through21`**, `containsExactly("1", …, "21")`. `SchemaMigrationIT` class Javadoc "V1–V18" → "V1–V21". Extend `queueEnums_afterMigration_areNotPgEnumTypes` to also assert `pg_type` has **no** `environment_health_status`, `environment_health_check_status`, `audit_outcome`.

**No migration for:** flaky detection (on-the-fly, §1), duration/slow analytics (§2), the Redis cache (§4), WebSocket (§5), rate limiting (§6 — Redis counters), timing metrics (§7 — Micrometer), HTTPS staging (§8 — config), CI scanning (§9).

### New endpoints

| Method + path | Purpose | RBAC | Notes |
|---|---|---|---|
| `GET /api/v1/analytics/flaky?projectId&window` | Per-`test_case_id` flakiness/stability over the last `window` results | `OWNER,ADMIN,MEMBER,VIEWER` | `orgId` from JWT; `projectId` required + org-checked; `window` clamp `[5,50]`; Redis-cached 30 s |
| `GET /api/v1/analytics/trends?projectId&days` | Daily run pass/fail + avg/p95 case duration | `OWNER,ADMIN,MEMBER,VIEWER` | `days` clamp `[1,90]`; zero-filled; cached 30 s |
| `GET /api/v1/analytics/slow?projectId&days&limit` | Top-`limit` slowest `test_case_id` by p95 `duration_ms` | `OWNER,ADMIN,MEMBER,VIEWER` | `limit` clamp `[1,100]`; `samples >= 3`; cached 30 s |
| `GET /api/v1/environments/{id}/health` | Current health + last 20 probe results | `OWNER,ADMIN,MEMBER,VIEWER` | Flat path; org-checked (`404` cross-org) |
| `GET /ws` (SockJS handshake) + STOMP `SUBSCRIBE /topic/runs/{runId}` | Live run status/progress | Handshake `permitAll`; JWT on STOMP `CONNECT`; `SUBSCRIBE` org-checked against the run | No REST body; the scope's `/ws/runs/{id}` = this subscription |

Additive-only. `POST /api/v1/runs` and `POST /api/v1/ci/runs` may now also return **`429 RATE_LIMITED`** + `Retry-After` (a standard code, not a contract break). All responses from those two endpoints now carry `X-RateLimit-Limit|Remaining|Reset`.

### New config properties (`qualityops.*`, with defaults)

```
# §1–2 analytics
qualityops.analytics.flaky.window-size                 20
qualityops.analytics.flaky.min-runs                    5
qualityops.analytics.slow.default-limit                20
qualityops.analytics.trends.max-days                   90

# §3 environment health (nested under qualityops.scheduling for jobs-enabled gating)
qualityops.scheduling.environment-health.enabled       true
qualityops.scheduling.environment-health.interval      PT60S      # job tick (ISO-8601 — @Scheduled)
qualityops.scheduling.environment-health.probe-interval PT5M      # per-env cadence
qualityops.scheduling.environment-health.probe-timeout  PT5S
qualityops.scheduling.environment-health.failure-threshold 3      # -> DOWN
qualityops.scheduling.environment-health.degraded-after 1         # -> DEGRADED
qualityops.scheduling.environment-health.batch-size     50
qualityops.scheduling.environment-health.history-retention P14D
qualityops.scheduling.environment-health.allow-private-targets false

# §4 dashboard cache
qualityops.cache.enabled                               true
qualityops.cache.dashboard-ttl                         PT30S

# §5 websocket
qualityops.ws.enabled                                  true
qualityops.ws.redis-channel                            qualityops:ws:runs
qualityops.ws.allowed-origins                          ${CORS_ORIGINS:http://localhost:5173,http://localhost:8090}

# §6 application rate limiting
qualityops.ratelimit.enabled                           true
qualityops.ratelimit.fail-open                         true
qualityops.ratelimit.run-trigger.limit                 60
qualityops.ratelimit.run-trigger.window               PT1H
qualityops.ratelimit.ci-run.limit                      120
qualityops.ratelimit.ci-run.window                    PT1H

# §7 audit / timing
qualityops.audit.enabled                               true
qualityops.timing.slow-threshold-ms                    1000
```

Also: `spring.task.scheduling.pool.size` `4 → 5` (tick, dispatch, reaper, webhook-dispatch, **environment-health-probe**; metrics-refresh + maintenance stay sub-second and queue behind). Gateway `application-staging.yml`: `server.ssl.*` + `GATEWAY_TLS_*` env vars (§8). `.env.example` gains the `GATEWAY_TLS_*` and (optional) `NVD_API_KEY` entries.

### New Micrometer meters (bounded cardinality, no `org` tag) & new annotations

| Meter | Type | Tags |
|---|---|---|
| `qualityops.analytics.query_duration` | timer | `query ∈ {flaky, trends, slow}` |
| `qualityops.environment.probe_duration` | timer | — |
| `qualityops.environment.health_transitions` | counter | `to ∈ {HEALTHY, DEGRADED, DOWN, UNKNOWN}` |
| `qualityops.environment.health` | gauge | `status` (count of ACTIVE staging/prod envs in each health state) |
| `qualityops.cache.errors` | counter | `op ∈ {get, put, evict, clear}` |
| `qualityops.ws.sessions` | gauge | — |
| `qualityops.ws.messages_sent` | counter | `scope ∈ {local, redis}` |
| `qualityops.ratelimit.rejected` | counter | `operation ∈ {run.trigger, ci.run}` |
| `qualityops.ratelimit.errors` | counter | — |
| `qualityops.slow_op` | timer | `op` (the `@Timed` value — a small, code-controlled set) |
| `qualityops.slow_op.exceeded` | counter | `op` |
| `qualityops.audit.written` | counter | `outcome ∈ {SUCCESS, FAILURE}` |
| `qualityops.scheduling.leader` | gauge (existing) | `job` gains `environment-health-probe` |

Spring Cache hit/miss is exposed via `RedisCacheManager.setEnableStatistics(true)` (`cache.gets{result}` auto-instrumented). `QueueMetrics` pre-registers the tagged counters above (matching ADR-007 §6's pattern) so a scrape sees them at 0.

**New annotations:** `com.qualityops.api.audit.annotation.Audited`, `com.qualityops.api.audit.annotation.Timed`, `com.qualityops.api.common.ratelimit.RateLimited`.

### New dependencies

| Dependency | Why | Alternatives rejected |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-aop` | Required for `@Aspect` (`aspectjweaver`) — §7. First-party, already implied by the roadmap. | *Manual `BeanPostProcessor` proxies* — reimplements what the starter gives for free. |
| `org.springframework.boot:spring-boot-starter-websocket` | STOMP + SockJS server and the `WebSocketStompClient` test util — §5. First-party; the plan names the test client. | *Raw `WebSocketHandler`* (needs the same starter, no multiplexing/fallback); *SSE* (no dep, one-way — recorded as the fallback, but roadmap/plan specify WebSocket); *RabbitMQ STOMP relay* (heavyweight new infra for a lab). |
| `org.springframework.boot:spring-boot-starter-cache` | `@EnableCaching` + Spring Boot's `RedisCacheManager` autoconfig — §4. First-party; `spring-data-redis` already present. | *Hand-rolled cache-aside* (duplicates TTL/serialization/tenancy logic per service); *Caffeine* (in-process, wrong for multi-replica). |
| `org.owasp:dependency-check-maven` (build plugin, `security-scan` profile) | §9 Java CVE gate. De-facto standard, SARIF output, NVD-backed. | *Snyk* (SaaS, token/seat); *`mvn versions:display-dependency-updates`* (freshness, not CVEs); *GitHub Dependabot alerts only* (no build-fail gate). |

---

## Consequences

### Positive

- **Meaningful analytics with no new write model.** Flaky/trends/slow are three native queries over `test_results` + `test_runs`, org-scoped, project-scoped, Redis-cached at 30 s. No incremental-stat table to drift; a `test_case_stats` materialisation stays a clean, deferred follow-up.
- **Environment health is one more boring `@Scheduled` job** on the ADR-006 infrastructure, sole-writer-safe, tenant-isolated, SSRF-guarded, and confined to STAGING/PRODUCTION so it never has to probe `localhost`. Health lives in a new `VARCHAR + CHECK` column — the admin `environment_status` PG enum is untouched.
- **The dashboard stops polling** without any Kafka change: lifecycle facts already consumed by `api-execution` / `api-results` are re-emitted through one outbound port to a STOMP topic, fanned out across replicas over a Redis channel using the already-present `spring-data-redis`. Backpressure is bounded by hard send-buffer/time limits.
- **Per-tenant operation limits** are enforced at the controller edge by a `@RateLimited` interceptor — immune to the AOP self-invocation trap, able to set `X-RateLimit-*` / `Retry-After`, and fail-open on a Redis outage so a cache/limiter problem never takes the API down.
- **Cross-cutting audit and timing** are real, tested Spring AOP aspects with documented ordering and an executable self-invocation test; `@Audited` promotes ADR-007's structured concurrency-change log line to a durable, org-scoped `audit_log` row.
- **Security posture rises**: HTTPS is documented (LB/ingress) with an opt-in gateway TLS profile for LB-less staging; CI now fails on a high/critical Java CVE, an `npm audit` high, or a `HIGH/CRITICAL` image finding, with governed, time-boxed suppression files.
- **Fully additive and reversible**: three append-only migrations, three first-party starters, one build-profile plugin, no `shared-events` change, no Worker change, no new Kafka topic, `apps/web` stays contract-compatible.

### Negative

- **`apps/api` gains a fifth leader-elected `@Scheduled` job** (`environment-health-probe`), a sixth ShedLock lock row, and `spring.task.scheduling.pool.size` rises to 5.
- **Three new starters + AspectJ proxying** slightly increase startup time and the proxy surface; every `@Transactional` service is already proxied, so the marginal cost is small but non-zero.
- **Two new tables** (`environment_health_check`, `audit_log`) that grow over time — bounded by the `QueueMaintenanceService` prune (`history-retention` 14 d for health checks) and, for `audit_log`, a Phase-4 retention policy (2E keeps it unbounded but low-volume — one row per audited mutation).
- **`RunLifecycleService.onRunCompleted`/`onRunFailed` do more on the terminal path**: a cache-evict `SCAN` and a best-effort WS publish, both after the existing retry/webhook hooks. Each is wrapped so a failure is logged, not propagated, but a slow Redis makes the consumer marginally slower.
- **The rate-limit `429` is a new response** from `POST /api/v1/runs` and `POST /api/v1/ci/runs`; `apps/web` should handle it (out of scope this increment — the code is standard).
- **The environment probe adds outbound HTTP from the API** — a new (guarded, limited) egress path that did not exist before.
- **Analytics module surface**: a new `AnalyticsController`, `AnalyticsService`, `AnalyticsRepository` + adapter, ~6 DTO records; plus the `realtime`, `audit`, and `common/ratelimit` / `common/net` packages — ~35 new classes.

### Risks

- **AOP self-invocation.** `@Audited`/`@Timed` on a method called via `this.other()` are silently ignored. Mitigation: documented stance (§7 — annotate the outermost entry point only, extract a bean if an inner step needs it, no `AopContext.currentProxy()`), a review rule in `.claude/rules/java-backend.md`, and `AopSelfInvocationTest` that asserts both the proxied hit and the direct-call miss.
- **Redis outage.** Cache: `LoggingCacheErrorHandler` fails open to Postgres (30 s TTL means minimal staleness). Rate limiter: `fail-open` allows the request, `qualityops.ratelimit.errors` fires, the gateway per-IP ceiling still holds. WS bridge: Redis publish failure degrades to local-only broadcast; clients on other replicas rely on reconnect / the dashboard's residual poll. All three are logged and metered; none takes the API down.
- **WebSocket backpressure / heap.** A slow consumer could grow the per-session outbound queue. Mitigation: `setSendBufferSizeLimit(512 KiB)` + `setSendTimeLimit(10 s)` + `setMessageSizeLimit(64 KiB)` — Spring disconnects an over-limit session rather than buffering; only lightweight `STATUS`/`CASE` frames are sent (never result/artifact bodies). `qualityops.ws.sessions` is the watch gauge.
- **Probe SSRF.** The API POSTs/GETs tenant-supplied `base_url`s. Mitigation: `OutboundAddressGuard` (loopback/link-local/site-local/CGNAT/ULA/`0.0.0.0/8`/broadcast/metadata denylist, shared with the webhook validator), STAGING/PRODUCTION only, `followRedirects(NEVER)`, 5 s timeout, status-line + 4 KiB read cap, `allow-private-targets` default false. Residual: a host that DNS-rebinds after validation — documented and accepted, identical to ADR-003 §5 / ADR-007 §Risks; IP-pinned fetch is a later hardening.
- **CI scan flakiness / false-positives.** NVD rate-limiting/outage: mitigated by `NVD_API_KEY` + a cached NVD DB (`~/.m2/.../dependency-check-data`). Mis-attributed CVEs: handled only by **time-boxed** `<suppress until="…">` entries in `.github/dependency-check-suppressions.xml` / dated lines in `.trivyignore`, each with a justification and a linked issue, both files `CODEOWNERS`-guarded. No `|| true`, no severity downgrade. A prolonged NVD outage is a documented "re-run after recovery" in `docs/runbooks/security-scanning.md`.
- **Cache eviction is per-org, not per-key.** A `runs.completed` `SCAN`-deletes all of an org's analytics + run-list entries. Cheap at lab write rates and tenancy-safe; a per-project prefix is a follow-up if the terminal event is confirmed to carry `projectId`.
- **Rolling-deploy skew.** Old + new API replicas: ShedLock serialises the new probe job (old replica simply lacks it — nothing fires twice); the WS Redis bridge on an old replica is absent, so its sessions miss cross-replica frames until it is replaced — no corruption. Deploy the API fleet together, as ADR-002…007 already require.
- **`audit_log` unbounded in 2E.** One row per audited mutation, low volume; a retention/rotation policy is a Phase-4 item (tracked).

---

## Alternatives considered

### Flaky detection
- **Materialised `test_case_stats` table (`org_id`, `test_case_id`, `runs_analyzed`, `pass_count`, `transition_count`, `flakiness`, `last_status`, `last_calculated_at`) updated on every `runs.completed`.** Rejected for 2E: incremental maintenance is a drift-bug magnet, needs a backfill migration, adds `org_id`/indexes, and the 30 s Redis cache already removes the per-request cost at lab data volumes. Kept as the clean follow-up if the window query profiles hot.
- **A Python/analytics service.** Rejected: Phase 6. The queries are plain SQL.

### Duration analytics
- **Store per-run aggregate duration on `test_runs` at terminal time.** Rejected: adds a mutable-ish column to the immutable run aggregate for a value `SUM/AVG(test_results.duration_ms)` already yields; violates domain rule #2's spirit.
- **A time-series database (Prometheus/Timescale) for durations.** Rejected: Prometheus is for operational metrics, not per-test business history; Timescale is a new datastore. Postgres `percentile_cont` is enough.

### Environment health
- **Extend the `environment_status` PG enum with `HEALTHY`/`DEGRADED`/`DOWN`.** Rejected: `ALTER TYPE … ADD VALUE` is not transaction-safe, hard to reverse, and conflates admin lifecycle with operational health. A separate `VARCHAR + CHECK` `health_status` column keeps them distinct (ADR-006 §3.1 reasoning).
- **Probe from the Worker.** Rejected: the Worker is not the sole writer of `environments`; the API is. A health probe is a cheap `HEAD`/`GET`, not test execution, and belongs on the API's existing scheduler.
- **Probe every environment including `DEV`.** Rejected: `DEV` `base_url`s are developer-local (`localhost`) — exactly the SSRF the denylist refuses. STAGING/PRODUCTION only, with an `allow-private-targets` escape hatch for self-hosted labs.
- **A new `runs.health`-style Kafka topic.** Rejected: no cross-service coordination needed; violates the "no new topic" constraint.

### Redis dashboard cache
- **Hand-rolled cache-aside in each service.** Rejected: duplicates serialization/TTL/tenant-key logic; easy to omit `orgId` from a key. Spring Cache with an explicit key expression makes tenancy reviewable.
- **Caffeine (in-process).** Rejected: multi-replica API ⇒ inconsistent per-replica caches; the plan specifies Redis.
- **Cache with `@CacheEvict(allEntries = true)` on completion.** Rejected as crude (cross-tenant nuke); the per-org `SCAN` prefix delete is barely more code and tenant-safe.

### WebSocket
- **Raw `WebSocketHandler` (no STOMP).** Rejected: still needs the starter, no subscription multiplexing, no SockJS fallback, and the plan's `WebSocketStompClient` test would not apply.
- **Server-Sent Events (`SseEmitter`).** Genuinely lighter (no new dependency, one-way is all we need, `EventSource` auto-reconnect) and **kept as the documented fallback** if STOMP proves troublesome through the gateway — but the roadmap and PHASE-2-PLAN explicitly say WebSocket and name the STOMP test client, so STOMP/SockJS is chosen.
- **RabbitMQ as a STOMP broker relay.** Rejected: a heavyweight new infrastructure dependency for a lab; the simple broker + Redis pub/sub bridge needs only `spring-data-redis`, already present.
- **A dedicated Kafka consumer group feeding the socket.** Rejected: the constraint forbids new topics/consumers; the existing `api-execution` / `api-results` handlers already have the facts — one outbound port call is enough.

### Application rate limiting
- **AOP aspect on `RunService.trigger` / `CiRunService.submit`.** Rejected: a service-layer `@Around` cannot set response headers (`X-RateLimit-*`, `Retry-After`) without request-scoped plumbing, and it inherits the self-invocation limitation. A `HandlerInterceptor` on the controller runs before the body, sets headers cleanly, and has no proxy to bypass.
- **Bucket4j + Redis.** Rejected: a new dependency for what a ~10-line Lua `INCR`+`PEXPIRE` fixed window does. The boundary-burst edge is documented and acceptable for a lab.
- **Enforce only at the gateway.** Rejected: the gateway limit is per-IP transport protection (ADR decision #10); per-`org_id` per-operation fairness needs the authenticated principal, which only the API has.
- **Fail-closed on Redis outage.** Rejected: a cache/limiter outage would then take down run triggering. Fail-open + metric + the gateway ceiling is the right trade for a lab.

### Spring AOP
- **Use Micrometer's `io.micrometer.core.annotation.Timed` + `TimedAspect`.** Partially — it is the stock choice — but rejected in favour of a self-contained `@Timed` so the slow-op semantics (threshold + WARN + `exceeded` counter) are explicit and there is no import ambiguity with our annotation. Micrometer's aspect shares the self-invocation limitation anyway.
- **Aspect writes `audit_log` rows directly from a repository.** Rejected: an aspect calling a repository skips the application layer and hexagonal dependency direction. The aspect calls `AuditRecorder` (an application service) which owns the port.
- **`@Audited` in `Propagation.REQUIRED` (join the business tx).** Rejected: a rolled-back business tx would lose the audit trail, and a failed audit insert would roll back the business action. `REQUIRES_NEW` + swallow-and-log decouples them; the "SUCCESS row for a later-rolled-back action" edge is documented.

### HTTPS in staging
- **Force `server.ssl.*` on the API too.** Rejected: TLS terminates at the edge (gateway / LB); the API stays HTTP on the pod network. Only the gateway gets the staging TLS profile.
- **Commit a self-signed keystore for staging.** Rejected: a keystore is secret material and must not be in git. The profile references it by env var only; the runbook shows how to generate/mount one.
- **Do k8s/Helm ingress TLS now.** Rejected: Phase 5. 2E is config + docs.

### CI scanning
- **Snyk / a SaaS scanner.** Rejected: token/seat management, external service. OWASP Dependency-Check + Trivy are free, offline-capable, SARIF-emitting, and standard.
- **Dependabot alerts only.** Rejected: alerts do not fail a build; the exit criterion is "CI fails on a planted vulnerable dependency".
- **`npm audit` on the full tree (incl. dev).** Rejected as noisy: dev-only advisories rarely ship. `--omit=dev --audit-level=high` gates the production tree; dev advisories are tracked, non-blocking.
- **A permanent "planted vuln" job.** Rejected: it would need a real vulnerable dep in the tree. The check is a documented one-off PR demonstration in the runbook.

---

## Documentation updates when 2E lands

- **`CLAUDE.md`** — add a "**Phase 2E … is COMPLETE** — see `docs/architecture/decisions/008-analytics-realtime-aop-hardening.md`" bullet under **CURRENT PHASE**, summarising: analytics (`/api/v1/analytics/{flaky,trends,slow}`), environment-health probe + `/api/v1/environments/{id}/health`, Redis dashboard cache (30 s, evict on `runs.completed`), STOMP-over-SockJS `/ws` + Redis fan-out, `@RateLimited` interceptor on the run/CI paths, `@Audited`/`@Timed` AOP + `audit_log`, gateway staging TLS profile, and CI dependency/image scanning. Change "Next increment is **Phase 2E**. Do NOT start it until told." → "Next increment is **Phase 2F**." Update the **Stack** table (API row: "+ analytics, environment-health probe, dashboard Redis cache, STOMP run-progress WebSocket, `@RateLimited` app-level limits, `@Audited`/`@Timed` AOP since 2E (ADR-008)"; add `audit` + `realtime` modules). Update the project-layout tree (`apps/api/.../{realtime,audit}/`, `apps/api/.../result/.../AnalyticsController.java`, `apps/gateway/.../application-staging.yml`, `docs/runbooks/{https-staging,security-scanning}.md`, `.github/dependency-check-suppressions.xml`, `.trivyignore`).
- **`ARCHITECTURE.md`** — new "### Phase 2E — analytics, real-time, AOP, hardening (ADR-008)" subsection under *Key design decisions*; add `realtime/` and `audit/` to the module list; *Data model* → V19 (analytics indexes), V20 (`environments.health_status` + `environment_health_check`), V21 (`audit_log`), with the "VARCHAR + CHECK, not PG enum" note on `health_status`/`outcome`; *API design → Endpoints* → add the four GETs + the `/ws` STOMP channel; *Execution flow* → note the WS push and cache-evict hooks on the terminal path; *Rate limiting* → add the "application level = `@RateLimited` interceptor, Redis fixed-window per `org_id` per operation, fail-open" row alongside the existing gateway row; *Security architecture → TLS/HTTPS strategy* → document LB/ingress termination + the gateway staging profile; *Technology decisions log* → rows for "Flaky detection = on-the-fly window query (no `test_case_stats`)", "Real-time = STOMP/SockJS simple broker + Redis pub/sub bridge", "App rate limiting = `@RateLimited` HandlerInterceptor", "Audit = `@Audited` AOP → `audit_log`", "CI security = OWASP Dependency-Check + `npm audit` + Trivy"; *Dependencies* → add the three starters + the `dependency-check-maven` plugin (profile).
- **`docs/product/PHASE-2-PLAN.md` §2E** — mark **✅ COMPLETE** with a pointer to ADR-008; note where the ADR narrows/deviates from the plan text: flaky detection uses **no `test_case_stats` table** (V19 is an analytics-index migration; env health is **V20**, audit is **V21**); environment health adds **`environments.health_status`** (a new `VARCHAR + CHECK` column), **not** transitions on the existing `environment_status` PG enum; rate limiting is a **`HandlerInterceptor`**, not an aspect; audit lives in a **`com.qualityops.api.audit` module**, not `common/audit/`.
- **`docs/product/ROADMAP.md`** — tick the Phase 2E line items (flaky detection, duration trends, environment health, Redis caching, WebSocket, application rate limiting, Spring AOP + proxy-behaviour tests, HTTPS staging, dependency + image scanning) with pointers to ADR-008.
- **New files** — `docs/architecture/decisions/008-analytics-realtime-aop-hardening.md` (this ADR), `docs/runbooks/https-staging.md`, `docs/runbooks/security-scanning.md`, `.github/dependency-check-suppressions.xml`, `.trivyignore`.

---

### Relevant absolute paths (for the planner/implementer)

- ADR to create: `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\docs\architecture\decisions\008-analytics-realtime-aop-hardening.md`
- Migrations dir: `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\apps\api\src\main\resources\db\migration\` (add `V19__…`, `V20__…`, `V21__…`)
- `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\apps\api\src\test\java\com\qualityops\api\persistence\SchemaMigrationIT.java` (extend to 1..21)
- `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\apps\api\src\main\resources\application.yml` (new `qualityops.*` blocks, pool size 4→5)
- `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\apps\api\pom.xml` (aop, websocket, cache starters)
- `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\pom.xml` (dependency-check `security-scan` profile)
- `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\.github\workflows\ci.yml` (`security-scan` job, `npm audit`)
- `c:\Users\mackr\Desktop\Cfold\CursorProjects\QaSaasLab\apps\gateway\src\main\resources\application.yml` (+ new `application-staging.yml`, `/ws/**` route)
- Existing hooks to touch: `...\apps\api\src\main\java\com\qualityops\api\execution\application\service\RunLifecycleService.java`, `...\result\application\service\ResultService.java`, `...\common\GlobalExceptionHandler.java`, `...\config\SecurityConfig.java`, `...\scheduling\application\service\QueueMaintenanceService.java`, `...\config\QueueMetrics.java`, `...\webhook\application\service\WebhookUrlValidator.java` (extract `OutboundAddressGuard`).
- New packages: `...\apps\api\src\main\java\com\qualityops\api\realtime\`, `...\audit\`, `...\common\ratelimit\`, `...\common\net\`, `...\result\...\AnalyticsController.java` + `AnalyticsService` + `AnalyticsRepository`, `...\environment\application\scheduler\EnvironmentHealthProbeJob.java` + health service/ports/adapters.
