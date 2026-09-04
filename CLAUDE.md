# Project: QualityOps Lab

An AI-native QA Platform Engineering sandbox that grows into a multi-tenant
SaaS. Teams can onboard applications, manage test environments, orchestrate
test runs, analyze results, detect flaky tests, and get AI-powered failure
analysis — all from a single dashboard.

## ⚠ CURRENT PHASE: 2 — Core Platform
**Only implement Phase 2 deliverables unless explicitly told otherwise.** Phase 1 is complete (see docs/product/ROADMAP.md).
- **Phase 2A (Worker extraction) is COMPLETE** — see `docs/architecture/decisions/002-worker-extraction.md`.
  - `apps/worker/` is a standalone Spring Boot app (`com.qualityops.worker`). It consumes `runs.requested`, executes each snapshot case, and publishes `runs.started` then one of `runs.completed` / `runs.failed`.
  - `apps/api/` keeps `RunKafkaPublisher`, the run-lifecycle back-consumers (`runs.started` / `runs.completed` / `runs.failed`, group `api-execution`) and the result consumer (`runs.completed`, group `api-results`). The API remains the **sole writer** of authoritative run/result state.
  - Kafka event contracts live in `packages/shared-events/` (`com.qualityops.events`), depended on by both `apps/api` and `apps/worker`.
- **Phase 2B1 (real API-test execution) is COMPLETE** — see `docs/architecture/decisions/003-real-api-execution.md`.
  - The Worker selects a runner **per case**: real HTTP (`ApiExecutionRunner`, JDK `HttpClient`) when the case carries an `ApiRequestSnapshot`, else simulated. SSRF-guarded + redacted; response memory bounded.
  - The Worker now has a datasource **scoped to a dedicated `worker` schema** (one table, its own Flyway stream) — a durable execution-attempt / dedup ledger, **not** authoritative state. It never touches `public` tables. This narrows ADR-002 §4 (see the amendment note in ADR-002).
  - `test_runs.execution_id` (V8, `NOT NULL UNIQUE`) guards every lifecycle transition (with `org_id`); `test_cases.api_request` (V9, JSONB) authors the spec.
- **Phase 2B2 (real Playwright browser execution) is COMPLETE** — see `docs/architecture/decisions/004-playwright-browser-execution.md`.
  - The Worker embeds **Playwright for Java** as a third `ExecutionRunner` (`BrowserExecutionRunner`, `kind()==BROWSER`) behind the existing port; all Playwright API is confined to `PlaywrightBrowserDriver` on a single-thread executor. Per-case selection precedence is `browserTest > apiRequest > simulated`.
  - The spec is **declarative** (`BrowserTestSnapshot`: navigate/click/fill/select/press-key steps; text/URL/visibility/element-state assertions; role/label/test-id selectors) — **no user JavaScript or shell**. `test_cases.browser_test` (V10, JSONB) authors it, mutually exclusive with `api_request`.
  - A **fresh `BrowserContext`** per execution; `page`→tracing→`context` closed in a guarded `finally`; a hard-timeout path `cancel(true)`s the future and force-recycles the shared `Browser`. `startUrl` + every `NAVIGATE` URL are SSRF-validated; private sub-resources are intercepted. Screenshots/traces are temp-only, size-capped, swept every 30 min (durable storage is 2B3). `persist-body-snippets` (default false) suppresses stored text snippets.
  - The Worker runtime image is now `mcr.microsoft.com/playwright/java` (bundled Chromium, glibc/jammy, ~2 GB). Browser ITs are `@Tag("browser")`; CI provisions Chromium.
- **Phase 2B3 (durable artifacts + per-case streaming + retry + `secretRef`) is COMPLETE** — see `docs/architecture/decisions/005-artifact-storage-and-result-streaming.md`.
  - **Durable artifact storage.** New Worker output port `ArtifactStoragePort` + one S3-compatible adapter (`S3ArtifactStorage`, MinIO Java client; Azure Blob is a Phase-5 adapter). Org-first, path-addressed keys (`org/<orgId>/run/.../attempt/<n>/<type>/<file>`), SSE-S3 by default, a bucket retention lifecycle rule (`BucketBootstrap` + the `minio-bootstrap` compose service). Upload is **synchronous best-effort on the per-case path, bounded by a 10s timeout, and NEVER blocks or fails the terminal** — a store problem yields an `UNAVAILABLE` `ArtifactReference`. MinIO joins the base compose stack (write-only key for the Worker, a **separate read-only key** for the API which only presigns — it never writes or proxies bytes).
  - **`results.chunk`** — new per-case streaming topic (key = runId, group `api-results`). One `ResultChunkEvent` per case after its retries + upload attempt. The API applies the same org- and `executionId`-guarded, epoch-monotone upsert as the terminal. Losing every chunk costs nothing: the v4 terminal reconciles `test_results` **and** `test_result_artifacts` (`CaseResultSummary` now carries `attemptEpoch` + `artifacts`).
  - **Bounded in-run retry** in `RunExecutionService.runCases`: re-invoke the same resolved runner for a transient `TIMEOUT`/`ERROR` only, with `SideEffectClass == NONE_OBSERVED` (never after a response status line / an interactive browser step / a genuine `FAILED`/`BLOCKED`), while the run wall-clock budget has room. No scheduler, no queue, no re-published request. `SideEffectClass` is worker-internal and never serialised.
  - **`secretRef`** — `HttpHeader.secretRef` / `BrowserStep.secretValue` carry an opaque key (`[A-Z0-9_]{1,64}`); the plaintext is resolved by the Worker at execution time (`EnvFileSecretResolver`; Azure Key Vault is Phase 5) and never enters an event, `config_snapshot`, a log line, `test_results`, or (by default) an artifact. Secret-sourced headers are always masked in `RequestMetadata`; secret-bearing browser screenshots are gated (`upload-secret-cases`, default false → `UNAVAILABLE:suppressed-secret-case`) with input masking + forced trace-off; an unresolvable `secretRef` ⇒ case `BLOCKED`.
  - `test_result_artifacts` + `test_results.attempt_epoch` land in migration **V11**. `RunRequestedEvent` / `RunCompletedEvent` are now `SCHEMA_VERSION = 4`, wire-compatible with v1–v3. `ResultChunkEvent.SCHEMA_VERSION = 1`. The Worker's Postgres reach is unchanged — still only `worker.execution_attempt`; the object store is a separate, write-scoped capability (a least-privilege `artifacts-rw` key).
- **Phase 2C (queue-driven scheduling & execution control) is COMPLETE** — see `docs/architecture/decisions/006-scheduling-and-queue.md` (incl. the *2C design-point resolutions & audit follow-ups* amendment). Verified 2026-09-03: `mvn verify` across all 4 modules (Testcontainers ITs incl. `SchedulingTickIT`, `RunCancellationIT`, `QueueDispatchFailureIT`, `QueueDispatchCancelRaceIT`, `QueueMetricsRefresherIT`); frontend lint/typecheck/vitest/build; `docker compose up` full stack + Playwright smoke e2e.
  - **`scheduling` module** in `apps/api` (`com.qualityops.api.scheduling`, hexagonal): one-time + recurring (6-field Spring cron, IANA time zone, DST-correct via `CronCalculator`) `Schedule` aggregate with pause/resume, `SKIP_MISSED` / `FIRE_ONCE` catch-up, and a live next-fires preview. `ScheduleTickJob` (`@Scheduled` + ShedLock `scheduling-tick`) scans a materialised `next_fire_at` and, per due schedule, `ScheduleFireService.fire(...)` runs its occurrence guard (`schedule_fire (schedule_id, fire_slot)` unique ledger ⇒ **at most one run per logical occurrence** across replicas and retried ticks), enqueues via the shared `EnqueueRunUseCase`, then advances `next_fire_at`.
  - **Leader coordination** — `net.javacrumbs.shedlock` (spring + jdbc-template provider) backed by the `shedlock` PostgreSQL table (V12, no `org_id` — a documented infra exception). `@EnableScheduling` + `@EnableSchedulerLock`, `.usingDbTime()`. Lock-store outage degrades to "nothing fires", never "fires twice".
  - **Authoritative `run_queue`** (V13, 1:1 with `test_runs`, `VARCHAR + CHECK` not PG enum). `RunService.trigger` / a fired schedule **enqueue** (`test_runs` PENDING + `run_queue` QUEUED with the fully-serialised `RunRequestedEvent` frozen in `requested_event_json` — a single-purpose mini-outbox) and **publish nothing**. `QueueDispatchJob` (`@Scheduled` + ShedLock `queue-dispatch`) selects candidates by an **aged effective-priority** `ORDER BY` (anti-starvation), enforces **per-org concurrency** (`max-active-runs-per-org` default 5, overridable via `org_run_concurrency` — read path only in 2C), then **claims (commit) → publishes `runs.requested` synchronously**. A lost send is reconciled **atomically** (`reconcileAfterFailedPublish`, one `TransactionTemplate` unit): rolled back to QUEUED, or — at `dispatch-max-attempts` / a corrupt frozen event — both `run_queue` **and** `test_runs` driven to `FAILED` (or `CANCELLED` if a cancel raced the send window). Two API replicas never double-dispatch (ShedLock + conditional claim `UPDATE`).
  - **`RunLifecycleService`** advances `run_queue` (`DISPATCHED→RUNNING→COMPLETED|FAILED`) only when the existing `executionId`-guarded `test_runs` `UPDATE` moved a row — no new guard column, redelivery-safe.
  - **Cancellation** — `POST /api/v1/runs/{id}/cancel`. A run cancelled while **QUEUED** is set `CANCELLED` in both tables with **no Kafka and no Worker** (the dispatcher's `WHERE queue_state='QUEUED'` guarantees it is never picked) — the only fully-guaranteed cancel (`200`). A `DISPATCHED`/`RUNNING` cancel is **cooperative** (`202`): `cancel_requested=true` + publish `RunCancelRequestedEvent` (standalone, **not** in the `RunEvent` seal; `SCHEMA_VERSION = 1`) on the new `runs.cancel` topic. The Worker consumes it (group `worker-execution`) into a bounded in-memory `CancellationRegistry` keyed by `executionId` — **no `run_queue` access, no Worker migration**. A pre-start cancel ⇒ claim + `runs.failed("execution cancelled before start")`; a mid-run cancel ⇒ remaining cases `ERROR "run cancelled"`, run still `runs.completed` (aggregate `FAILED`). `RunCancellationService` uses a plain read + guarded conditional `UPDATE`s with a fall-through re-read (race-correct without `FOR UPDATE`) and publishes the cancel command **after** commit. The only remaining stranded state — a crash between the dispatch-claim commit and `send()` (`run_queue`=DISPATCHED + `test_runs`=PENDING) — is deferred to the 2D reaper.
  - **Observability** — Micrometer meters on the Prometheus surface (`micrometer-registry-prometheus`; `management` exposes `metrics,prometheus`): `qualityops.queue.depth{priority}`, `oldest_age_seconds`, `wait_seconds`, `dispatch_throughput`, `dispatch_failed{reason}`, `active_runs`, `cancellations{phase}`, `schedule.fires{outcome}`, `tick_duration` / `dispatch_duration`, `scheduling.leader{job}` (wired from the ShedLock-locked job bodies) — no `org` tag (bounded cardinality). Gauge refresh runs in `QueueMetricsRefresher`, gated on `qualityops.scheduling.jobs-enabled`.
  - Migrations **V12–V15** (`shedlock`, `run_queue`, `schedule` + `schedule_fire`, `org_run_concurrency`). **No `run_status` enum change** (`CANCELLED` already present). **No Worker migration.** `SchemaMigrationIT` version list is now 1..15.
- **Phase 2D (stuck-run reaper + queue-driven retry + idempotent CI API + Caseflow contract & signed webhooks) is COMPLETE** — see `docs/architecture/decisions/007-queue-reaper-retry-ci-caseflow.md`. **No `shared-events` change, no Worker change, no new Kafka topic** — the reaper and retry both re-publish to the existing `runs.requested`; webhooks are HTTP.
  - **`StuckRunReaper`** (`@Scheduled` + ShedLock `stuck-run-reaper`, gated on `jobs-enabled`) → `StuckRunReaperService.sweep()`: **(a)** a stranded `DISPATCHED` row (`dispatched_at` older than `reaper.dispatch-grace` PT2M but newer than `reaper.run-timeout` PT30M, `test_runs` still PENDING) is re-claimed (`reclaimStranded`, own committed tx) and **re-published** via the extracted package-private `QueueDispatchService.publishClaimed(candidate, alreadyClaimed)` — idempotent (the Worker's `worker.execution_attempt` claim absorbs the duplicate); a cancel that raced the window is reconciled to `CANCELLED`. **(b)** a stuck `DISPATCHED`/`RUNNING` run past `run-timeout` is driven to `FAILED` in **both** tables (`RunRepository.reapToFailed`, org-scoped `status IN (PENDING,RUNNING)` guard, **no `executionId`**), `run_queue` gated on `rows > 0` — **no Kafka**. Redelivery-safe, foreign-org rows untouched.
  - **Queue-driven retry** — `RunLifecycleService.onRunFailed`, in the same `@Transactional` unit, gated on the `moved` boolean (the sole dedup point): `RetryRunService.retryIfEligible` checks a **reason-prefix denylist** (`retry.non-retryable-reason-prefixes` = `execution cancelled`, `run cancelled`), a **per-run budget** (`retry.max-per-run` 2, `run_queue.retry_count` monotone along the chain) and a **per-org rolling-window budget** (`retry.max-active-per-org` 20 / `retry.window` PT1H, a live `COUNT`). Eligible ⇒ `RunEnqueueService.enqueueRetry`: a **fresh immutable `test_runs`** row with the **byte-identical** `config_snapshot` (domain rule #2 — no re-freeze), a new `run_queue` QUEUED row with `retry_of` + `retry_count+1`, the frozen `RunRequestedEvent` rebuilt with new `eventId`/`runId`/`executionId`/`occurredAt` but the **original `correlationId`**. Publishes nothing — the dispatcher picks it up a tick later. A `runs.completed` with aggregate `FAILED` is **never** retried. `RunResponse` gains additive-nullable `retryOf` / `retryCount`.
  - **`org_run_concurrency` write path** — `PUT|GET /api/v1/admin/orgs/{orgId}/run-concurrency` (`OWNER`/`ADMIN`, own org only — cross-org is Phase 4), `{maxActiveRuns}` `@Min(1) @Max(1000)` (the `@Max` lives only in the DTO; V15's CHECK is just `> 0`). `OrgConcurrencyService` upserts and emits a structured audit line (`logger com.qualityops.api.audit`, `action=org.run_concurrency.update`). The dispatcher honours the override on the next tick, no restart. **No migration.**
  - **`GET /api/v1/admin/queue`** (`OWNER`/`ADMIN`, org-scoped) — `{org:{queuedByPriority, oldestQueuedAgeSeconds, activeRuns, effectiveMaxActiveRuns, maxActiveRunsSource}, process:{dispatchThroughput, dispatchFailed, reaped, retries}}`; `org` from dedicated `…ForOrg` queries, `process` read straight off the `MeterRegistry`. `infra/grafana/queue-dashboard.json` is an importable dashboard (no compose wiring yet).
  - **Idempotent `POST /api/v1/ci/runs`** — header `Idempotency-Key` (`[A-Za-z0-9_.\-]{1,200}`, blank/missing/oversize ⇒ 400), body = `CreateRunRequest`. **200 on the first call AND every same-key+same-body call**; a same-key different-body call ⇒ **409 `IDEMPOTENCY_KEY_CONFLICT`** (`request_fingerprint` = SHA-256 of `projectId|suiteId|environmentId|priority`, priority normalised). `CiRunService` (not class-`@Transactional`) runs one `TransactionTemplate` unit (`enqueue` + `INSERT ci_idempotency_key`); a concurrent first-call race hits `UNIQUE (org_id, idempotency_key)` ⇒ `DataIntegrityViolationException` ⇒ the whole unit rolls back (no orphan run) ⇒ catch-and-re-read returns the winner. `QueueMaintenanceService.prune()` also sweeps `ci_idempotency_key` older than `qualityops.ci.idempotency-retention` (P7D) and terminal `webhook_delivery` older than `qualityops.webhook.delivery-retention` (P7D).
  - **Caseflow contract** — `docs/api/caseflow-v1.yaml` (hand-maintained OpenAPI 3.1, `info.version 1.0.0`) is the versioned external *description* of five existing operations (`submitCaseflowRun` / `getRun` / `cancelRun` / `listRunResults` / `listRunArtifacts`) + the completion-webhook payload + signature headers; `CaseflowContractTest` (SnakeYAML structural) fails the build if an operationId or the `RunCompletedWebhook` schema goes missing. `docs/api/ci-execution.md` has GitHub Actions / GitLab CI / Jenkins `curl`+poll snippets (no plugin).
  - **`webhook` module** (`com.qualityops.api.webhook`, hexagonal-lite) — `POST|GET /api/v1/projects/{projectId}/webhooks`, `DELETE /api/v1/webhooks/{id}` (`OWNER`/`ADMIN`, own project). `RunLifecycleService` (gated on `moved`, same tx) calls `EnqueueRunWebhooksUseCase.enqueueForTerminalRun` which inserts frozen-payload `webhook_delivery` rows (`UNIQUE (run_id, webhook_endpoint_id)` ⇒ a redelivered terminal is a no-op). `WebhookDispatchJob` (`@Scheduled` + ShedLock `webhook-dispatch`) → `WebhookDeliveryService.dispatchDue()` POSTs via JDK `HttpClient` with `X-QualityOps-Event|Delivery|Timestamp|Signature` (`sha256=` HMAC-SHA256(secret, `"<ts>.<body>"`)), exponential backoff to `EXHAUSTED` after `webhook.max-attempts` (6). `WebhookUrlValidator` is https-only + private-IP denylist (lighter than the Worker's `TargetValidator`). **The secret is plaintext at rest in 2D** (masked as `secretSet:true` in every response) — column encryption / Key-Vault indirection is a Phase-4 hardening.
  - Migrations **V16–V18** (`run_queue.retry_of`+`retry_count`, `ci_idempotency_key`, `webhook_endpoint`+`webhook_delivery`; `webhook_delivery.state` is `VARCHAR + CHECK`, not a PG enum). `spring.task.scheduling.pool.size` `2 → 4`. New meters: `qualityops.queue.reaped{kind}`, `qualityops.queue.retries{outcome}`, `qualityops.webhook.delivery{outcome}`, `qualityops.scheduling.reaper_duration`, `qualityops.webhook.delivery_duration`, `qualityops.scheduling.leader{job}` gains `stuck-run-reaper` / `webhook-dispatch`. `SchemaMigrationIT` version list is now 1..18.
- **Phase 2E (analytics + real-time dashboard + app-level rate limiting + AOP cross-cutting concerns + HTTPS-staging + CI security scanning) is COMPLETE** — see `docs/architecture/decisions/008-analytics-realtime-aop-hardening.md`. **No `shared-events` change, no Worker change, no new Kafka topic.** Additive read paths, an edge guard, cross-cutting observers, and CI/config only. Three first-party starters (`spring-boot-starter-{aop,websocket,cache}`) + the `org.owasp:dependency-check-maven` plugin behind a `security-scan` Maven profile.
  - **Analytics** — new `AnalyticsController` (`result` module): `GET /api/v1/analytics/flaky?projectId&window` (per-`test_case_id` flakiness/stability = transitions ÷ (runs−1) over the last N results; alternating ⇒ ~1.0, all-pass/all-fail ⇒ 0.0), `GET /api/v1/analytics/trends?projectId&days` (daily run pass/fail + avg/p95 case duration, zero-filled), `GET /api/v1/analytics/slow?projectId&days&limit` (top-N `test_case_id` by p95 `duration_ms`). Three native window/aggregate queries over `test_results` + `test_runs` (`percentile_cont(0.95)`), org- + project-scoped, **no materialised stats table** — `V19` is an analytics-index migration.
  - **Environment health monitoring** — a fifth leader-elected `@Scheduled` job (`environment-health-probe`, ShedLock, gated on `jobs-enabled`) probes `STAGING`/`PRODUCTION` env `base_url`s (JDK `HttpClient`, `followRedirects(NEVER)`, timeout-bounded, body discarded) and classifies `HEALTHY|DEGRADED|DOWN` (`degraded-after` 1, `failure-threshold` 3). New `environments.health_status` (`VARCHAR + CHECK`, **not** the admin `environment_status` PG enum) + `last_probe_at`/`last_healthy_at`/`consecutive_failures`, and an `environment_health_check` history table (**V20**, `org_id NOT NULL`, swept by `QueueMaintenanceService.prune()` at `history-retention` P14D). `GET /api/v1/environments/{id}/health`. The extracted `common/net/OutboundAddressGuard` (shared with `WebhookUrlValidator`) blocks loopback/link-local/metadata/CGNAT/ULA/broadcast; the probe's network I/O runs **outside** any DB transaction.
  - **Redis dashboard cache** — `spring-boot-starter-cache` + `@EnableCaching` + a `RedisCacheManager` (30 s TTL, per-cache key prefix embedding `orgId` so entries are tenant-partitioned by construction). `@Cacheable` on the three analytics reads and `RunService.list` (caches `analytics.{flaky,trends,slow}` + `runs.list`). A `LoggingCacheErrorHandler` **fails open** to Postgres on any Redis error (`qualityops.cache.errors`). `DashboardCacheInvalidator.evictForOrg` (in `config`, no module cycle) is called from `RunLifecycleService` after a terminal transition moved a row — `SCAN`+delete `*::{orgId}:*`, per-org, self-swallowing.
  - **WebSocket run progress** — `realtime` module: STOMP-over-SockJS endpoint `/ws` (handshake `permitAll`; JWT validated on STOMP `CONNECT`; `SUBSCRIBE /topic/runs/{runId}` is org-checked via `GetRunUseCase` — the socket's tenant boundary), in-memory simple broker on `/topic`, hard send-buffer/time/message-size limits (backpressure guard). The existing `api-execution` / `api-results` consumers push `RunProgressEvent` through a new `RunProgressNotifier` output port (in `execution`; **no new Kafka topic, no new consumer**); a `StringRedisTemplate` pub/sub channel (`qualityops:ws:runs`) + `RedisRunEventBridge` fan-out re-broadcasts across API replicas, degrading to local-only on a Redis publish failure. Every push is best-effort (`try/catch`, never rolls back a consumer tx). Gateway gains a `/ws/**` route with **no** `RequestRateLimiter`.
  - **Application-level rate limiting** — `@RateLimited(operation, limit, window)` + a Spring MVC `HandlerInterceptor` (NOT an aspect — sets response headers, immune to the AOP self-invocation trap) on `POST /api/v1/runs` (`run.trigger`, 60/h) and `POST /api/v1/ci/runs` (`ci.run`, 120/h). Redis fixed-window `INCR`+`PEXPIRE` per `(orgId, operation, window)`; over-limit ⇒ `429 RATE_LIMITED` + `Retry-After` + `X-RateLimit-{Limit,Remaining,Reset}`. **Fails open** on a Redis error (`qualityops.ratelimit.errors`). Distinct from the gateway's per-IP `RequestRateLimiter`.
  - **Spring AOP** — new `audit` module: `@Audited(action, targetType)` → an `AuditAspect` (`@Order(10)`, inner) that writes an `audit_log` row (**V21**, `org_id NOT NULL`, `outcome VARCHAR + CHECK`) via `AuditRecorder` (`REQUIRES_NEW` + swallow-and-log, so an audit failure never breaks or rolls back the business call); rethrows the original exception unchanged on failure; `detail` JSON built with Jackson. `@Timed(value, slowThresholdMillis)` → a `TimingAspect` (`@Order(0)`, outermost) recording `qualityops.slow_op{op}` and `qualityops.slow_op.exceeded{op}` + a WARN past the threshold (falls back to `qualityops.timing.slow-threshold-ms` when the annotation leaves it 0). `@Audited` applied to `OrgConcurrencyService.set`, `EnvironmentService.{create,update,delete}`, `ProjectService.delete`, `TestSuiteService.delete`, `WebhookEndpointService.{register,delete}`; `@Timed` on `RunService.trigger`. **Self-invocation limitation** documented in the ADR + `.claude/rules/java-backend.md` and pinned by `AopSelfInvocationTest` (proxied call fires; `this.other()` does not) — annotate only the outermost proxied entry point.
  - **HTTPS in staging** — config + docs only (k8s/Helm ingress TLS is Phase 5). `apps/gateway/src/main/resources/application-staging.yml` enables `server.ssl.*` from env vars only (no committed keystore); the recommended path terminates TLS at the LB/ingress (`GATEWAY_TLS_ENABLED=false`). HSTS is unchanged (already emitted by the gateway). `docs/runbooks/https-staging.md`; `GatewayStagingProfileIT` proves the profile boots.
  - **CI security scanning** — `.github/workflows/ci.yml` gains a `security-scan` job: OWASP Dependency-Check (`mvn -Psecurity-scan verify`, `failBuildOnCVSS=7`) + Trivy image scans (`HIGH,CRITICAL`, `exit-code 1`, `ignore-unfixed`), SARIF uploaded to code scanning; `npm audit --audit-level=high --omit=dev` folded into the `web` job. Suppressions live in time-boxed, `CODEOWNERS`-guarded `.github/dependency-check-suppressions.xml` / `.trivyignore` — no `|| true`, no severity downgrade. `docs/runbooks/security-scanning.md` documents the planted-vulnerable-dependency exit check. Baseline `npm audit` highs cleared by bumping `axios` → `1.20.0` and `react-router-dom` → `6.30.6`.
  - Migrations **V19–V21** (analytics indexes; `environments.health_status` + `environment_health_check`; `audit_log`) — all append-only, every new table carries `org_id NOT NULL`, `health_status`/`outcome` are `VARCHAR + CHECK` not PG enum. `spring.task.scheduling.pool.size` `4 → 5`. Also fixed a pre-existing `GET /api/v1/runs` 500 (untyped enum bind param in `RunJpaRepository.findAllByOrgId`, now `CAST(:status AS string)`). `SchemaMigrationIT` version list is now 1..21.
- **Phase 2F (repository-owned framework execution) implementation is COMPLETE; full-stack verification is PENDING (WP12)** — see `docs/architecture/decisions/009-repository-owned-framework-execution.md`. **Scope decision (2026-09-04): suite-authored only** — a repo test case is authored via the case editor's "Repository" tab and runs through the existing suite Run-now / CI / schedule flows; there is **no** ad-hoc "run now from a connection" endpoint (`test_runs.suite_id` stays `NOT NULL FK`, no `test_runs` migration).
  - **`scm` module** (`apps/api`, hexagonal) — repository-connection CRUD + an outbound "test connection" probe (`ScmPort`/`GitHubScmAdapter`/`GitLabScmAdapter`, JDK `HttpClient`). `RepositoryRunPreflightService` (`ResolveRepositoryRunUseCase`) resolves a mutable ref to an immutable 40-hex commit SHA and freezes the digest-pinned `runnerImageRef` **inside `RunEnqueueService.enqueue`, before `test_runs` is inserted** (domain rule #2) — any failure rolls the whole enqueue back, no orphan row. A retry re-runs the frozen SHA; a schedule fire re-resolves the ref. Host allowlist + `OutboundAddressGuard` (ADR-008 §3 reused); `credentialRef` is an opaque key only, resolved separately by the API (ref-resolution/probe) and the Worker (checkout), never stored.
  - **Kafka — additive only, no new topic.** `TestCaseSnapshotItem` gains a 6th nullable `RepoTestSnapshot`; `RunRequestedEvent`/`RunCompletedEvent` `4 → 5`; `ResultChunkEvent` `1 → 2` (both gain `repositoryItems` + `repositoryProvenance`). A repository run is one `TestCaseSnapshotItem` per run; the framework's own report is parsed into N `RepositoryTestItem`s.
  - Migrations **V22–V25** (`apps/api` only — **no worker migration**, `worker.execution_attempt.runner_kind='REPOSITORY'` is a new free-text value): `V22 repository_connection`, `V23 test_cases.repo_test` (JSONB, mutually exclusive with `api_request`/`browser_test`), `V24 repository_run` (1:1 with `test_runs`, frozen spec + execution-telemetry columns), `V25 repository_test_item` (normalized per-test rows, epoch-guarded upsert, kept separate from `test_results`). All new tables `org_id NOT NULL`; every enum-like column `VARCHAR + CHECK`.
  - **Worker** — `RepositoryExecutionRunner` (`kind()==REPOSITORY`, **unconditional precedence** over browser/API — gap #8) orchestrates two hardened sibling containers per attempt through a new output port `ContainerRunnerPort` (`DockerContainerRunner`, `docker-java` + `docker-java-transport-httpclient5`): a *checkout* container (`git fetch --depth 1` of the frozen SHA, `EGRESS` network) then a *framework* container (the repo's own argv command, `ISOLATED`/`NetworkMode.NONE` by default or `EGRESS`). Non-root, `CapDrop ALL`, read-only rootfs, no Docker socket, no `--privileged`, resource-limited (`RepoResourceProfile`), bind-mounted per-attempt workspace deleted in a `finally`. `SideEffectClass` flips `NONE_OBSERVED → POSSIBLE` once the framework container starts (gap #5) — in-run retry only covers a pre-exec failure. Dedup rides the existing `worker.execution_attempt` claim + a deterministic container name with adopt-or-recreate; `RepoContainerSweeper` label-sweeps orphans. An unregistered `REPOSITORY` runner (rolling-deploy skew) resolves to a `BlockedRepositoryRunner` sentinel, never an NPE or a simulated fallback. Cancellation reuses the unchanged `runs.cancel` path (SIGTERM → grace → SIGKILL).
  - **Runner-image allowlist** — `qualityops.repo-exec.images.<preset>`, one digest-pinned ref per `FrameworkPreset` + `checkout`, enforced independently by the API (freezes only an allowlisted value) and the Worker (refuses a non-allowlisted `imageRef` pre-create, refuses a pulled-digest mismatch post-pull). All six digests are **real, resolved** refs (`docker pull` → `docker inspect`), version-controlled and `CODEOWNERS`-guarded in `infra/compose/runner-images.env` (the single source of truth for `application.yml` defaults and CI's Trivy scan matrix) — never a placeholder.
  - **Report parsing** — `JUnitXmlReportParser` (Playwright/JUnit-Surefire/pytest/Cypress) + `K6SummaryReportParser` (k6 summary-JSON; run-level pass/fail from the exit code is exact, item breakdown best-effort). `WorkspacePathResolver` rejects any glob match outside the workspace root (zip-slip guard). A malformed report ⇒ case `ERROR`, run not aborted.
  - **Secrets** — `secretVars`/`credentialRef` resolved by the Worker at execution time (`EnvFileSecretResolver`); the checkout token lives only in the checkout container's tmpfs. `Redactor.forExecution(Set<String>)` masks every resolved secret + the checkout token on top of the existing regex rules. A secret-bearing run gates raw artifact upload (`upload-secret-run-artifacts`, default false → `UNAVAILABLE:suppressed-secret-run`); parsed items always flow.
  - **Compose network split** — `qualityops-internal` (`internal: true`; postgres/redis/kafka/minio + api/worker/gateway) + `qualityops-runner-egress` (plain bridge; only an `EGRESS`-policy repo-run container joins it, never via compose). A pinned `docker-proxy` (`tecnativa/docker-socket-proxy`) fronts the host socket for the Worker with a verb allowlist (no `/exec`/`/commit`/`/build`/`/volumes`/Swarm); `require-proxy=true` fails Worker startup on a raw-socket `DOCKER_HOST` (raw socket accepted only for local `mvn spring-boot:run`, loud WARN).
  - **Frontend (additive)** — a "Repositories" project tab (`RepositoryConnectionsTab`/`RepositoryConnectionForm`), a "Repository" tab in the case editor (`RepoTestForm` — selecting a connection marks a case as repository-run), and run-detail additions (`RepositoryExecutionPanel`, `RepositoryTestItemsTable`); `apps/web/src/api/repositories.ts`. `GET /api/v1/runs/{id}` / `.../results` gained additive-nullable `repositoryRun` / `meta.repositoryItems`. No new routes, no "run now from a connection" UI.
  - New Micrometer meters `qualityops.repo.*` (bounded cardinality, no `org` tag). Two new runtime deps, both CI-scanned: `docker-java-core`+`docker-java-transport-httpclient5` (Worker only) and `tecnativa/docker-socket-proxy` (infra image).
  - **WP12 (full-stack verification: `docker compose up` against the network-split topology + the `repository-run` Playwright smoke) has not run yet** — do not describe Phase 2F as fully verified until it does.
- Next increment is **Phase 2F**. Do NOT start it until told.
- Do NOT implement OAuth/SSO (Phase 4), Stripe (Phase 4B), Terraform (Phase 5),
  or AI agent features (Phase 6).
- See `docs/product/ROADMAP.md` for Phase 2 scope and `docs/product/PHASE-2-PLAN.md` for the 2A–2F increment breakdown.
- **When the user says to move to the next phase, update this section.**

## Stack

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React 18 + TypeScript + Vite | TanStack Query, Tailwind CSS |
| Backend API | Java 21 + Spring Boot 3 | Modular monolith; HTTP + run-lifecycle/result Kafka consumers; sole DB writer; ShedLock leader-elected scheduler + DB-ordered queue dispatcher since 2C (ADR-006); + stuck-run reaper + queue-driven retry + idempotent CI API + signed completion webhooks (`webhook` module) since 2D (ADR-007); + flaky/duration analytics, environment-health probe, Redis dashboard cache, STOMP run-progress WebSocket (`realtime` module), `@RateLimited` app-level limits, `@Audited`/`@Timed` AOP + `audit_log` (`audit` module) since 2E (ADR-008); + `scm` module (repository connections, ref→SHA resolution at enqueue) since 2F (ADR-009, implementation complete, WP12 verification pending) |
| Worker | Java 21 + Spring Boot 3 | Standalone since Phase 2A (ADR-002); real API runner + SSRF guard since 2B1 (ADR-003); embedded Playwright-Java browser runner since 2B2 (ADR-004); durable artifact upload + `results.chunk` + bounded retry + `secretRef` since 2B3 (ADR-005); cooperative cancel via `runs.cancel` + in-memory `CancellationRegistry` since 2C (ADR-006); datasource limited to its own `worker` schema (dedup ledger); write-scoped artifact bucket key; runtime image `mcr.microsoft.com/playwright/java`; + repository-owned framework execution via two disposable, hardened sibling Docker containers per attempt (`ContainerRunnerPort`/`DockerContainerRunner`, digest-pinned image allowlist) since 2F (ADR-009, implementation complete, WP12 verification pending) |
| Object storage | MinIO (S3-compatible) | Test artifacts (screenshots/traces) since 2B3 (ADR-005). Local/dev in compose; Azure Blob is a Phase-5 adapter behind `ArtifactStoragePort`. Worker writes (write-only key); API presigns GET (separate read-only key) |
| Shared events | Java 21 (plain jar) | `packages/shared-events` — Kafka event contract records (`com.qualityops.events`); `RunRequestedEvent`/`RunCompletedEvent` `SCHEMA_VERSION = 5`, `ResultChunkEvent = 2`, `RunCancelRequestedEvent = 1` (standalone, outside the `RunEvent` seal) — bumped to v5/v2 in 2F (ADR-009) for the additive `RepoTestSnapshot`/`repositoryItems`/`repositoryProvenance` fields |
| Gateway | Spring Cloud Gateway | Routing, rate limiting, auth |
| Database | PostgreSQL 16 | Primary data store |
| Cache | Redis 7 | Sessions, rate limits, run state |
| Messaging | Apache Kafka | Event-driven run orchestration |
| E2E Testing | Playwright | Via MCP + direct runner |
| CI/CD | GitHub Actions | Lint → test → build → deploy |
| Containers | Docker Compose (local) | AKS + Helm later; network split (`qualityops-internal`/`qualityops-runner-egress`) + `docker-proxy` since 2F (ADR-009) |
| Observability | OpenTelemetry + Prometheus + Grafana | Traces, metrics, logs |

## Project layout

```
.
├── CLAUDE.md                         # this file — always in context
├── ARCHITECTURE.md                   # system design, decisions, diagrams
├── .mcp.json                         # MCP server config (Playwright, etc.)
├── .gitignore
│
├── apps/
│   ├── web/                          # React frontend
│   │   ├── package.json
│   │   ├── tsconfig.json
│   │   ├── vite.config.ts
│   │   ├── src/
│   │   │   ├── main.tsx
│   │   │   ├── App.tsx
│   │   │   ├── api/                  # API client layer (TanStack Query)
│   │   │   ├── components/           # shared UI components
│   │   │   ├── features/             # feature modules (projects, runs, etc.)
│   │   │   ├── hooks/                # custom React hooks
│   │   │   ├── layouts/              # page layouts
│   │   │   ├── pages/                # route pages
│   │   │   └── types/                # shared TypeScript types
│   │   └── tests/                    # Vitest unit + component tests
│   │
│   ├── api/                          # Spring Boot main backend
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/qualityops/api/
│   │       │   ├── QualityOpsApplication.java
│   │       │   ├── config/           # Spring config, security, Kafka
│   │       │   ├── identity/         # auth, users, roles, tenants
│   │       │   ├── project/          # projects, workspaces
│   │       │   ├── environment/      # environment registry + health probe (ADR-008)
│   │       │   ├── testsuite/        # test catalog: suites, cases, tags (incl. repo_test authoring, ADR-009)
│   │       │   ├── execution/        # run orchestration: queue dispatch, stuck-run reaper, queue-driven retry, CI API (ADR-006/007); RepositoryRunPreflight call site (ADR-009)
│   │       │   ├── scheduling/       # ShedLock scheduler, run_queue + health-check maintenance (ADR-006/007/008)
│   │       │   ├── scm/              # repository connections, ref→SHA resolution at enqueue, digest-pinned image allowlist (ADR-009)
│   │       │   ├── webhook/          # outbound signed run-completion webhooks (ADR-007)
│   │       │   ├── realtime/         # STOMP run-progress WebSocket + Redis fan-out (ADR-008)
│   │       │   ├── audit/            # @Audited/@Timed AOP aspects + audit_log (ADR-008)
│   │       │   ├── result/           # results, analytics (flaky/trends/slow), flakiness; repository_test_item reads (ADR-009)
│   │       │   ├── testdata/         # test data management
│   │       │   ├── mock/             # dependency virtualization
│   │       │   └── ai/              # AI assistant integration
│   │       └── test/                 # JUnit 5 + Testcontainers
│   │
│   ├── worker/                       # standalone Kafka worker (Phase 2A, ADR-002); `worker`-schema dedup ledger (2B1); embedded Playwright browser runner (2B2); repository-owned framework execution via disposable Docker containers (2F, ADR-009)
│   │   ├── pom.xml
│   │   └── src/main/java/com/qualityops/worker/
│   │       ├── WorkerApplication.java
│   │       ├── config/               # Kafka consumer + dead-letter config; HttpClient; PlaywrightConfig (single-thread executor); RepoExecWorkerProperties (ADR-009)
│   │       └── execution/            # runs.requested consumer → per-case runner (repository | browser | API | simulated, this precedence) → runs.started/completed/failed; ContainerRunnerPort/DockerContainerRunner + report parsers (ADR-009)
│   │
│   ├── ai-agent/                     # Python AI service (Phase 6)
│   │   ├── pyproject.toml
│   │   └── app/
│   │       ├── main.py               # FastAPI entry point
│   │       ├── agents/               # LangChain tool-use agents
│   │       ├── chains/               # RAG + analysis chains
│   │       ├── tools/                # agent tools (API, Git, Playwright)
│   │       ├── embeddings/           # vector store + indexing
│   │       └── prompts/              # prompt templates
│   │
│   └── gateway/                      # API gateway / reverse proxy
│       ├── pom.xml
│       └── src/main/java/com/qualityops/gateway/
│           ├── GatewayApplication.java
│           ├── config/               # routes, filters, rate limiting
│           └── filter/               # custom gateway filters
│
├── packages/
│   ├── shared-types/                 # shared HTTP / OpenAPI DTOs (web ↔ api)
│   │   └── README.md
│   └── shared-events/                # Kafka event contracts (com.qualityops.events) — api + worker
│       ├── pom.xml
│       └── src/
│
├── infra/
│   ├── docker/
│   │   ├── Dockerfile.api
│   │   ├── Dockerfile.worker
│   │   ├── Dockerfile.gateway
│   │   └── Dockerfile.web
│   ├── compose/
│   │   ├── docker-compose.yml        # full local stack — network split (qualityops-internal/-runner-egress) + docker-proxy since 2F (ADR-009)
│   │   ├── docker-compose.dev.yml    # dev overrides
│   │   └── runner-images.env         # digest-pinned repository-run runner-image allowlist, single source of truth (ADR-009)
│   ├── terraform/                    # IaC: Azure resources (Phase 5)
│   │   ├── modules/                  # reusable: aks, database, redis, etc.
│   │   └── environments/             # staging/ and production/ configs
│   ├── k8s/                          # raw manifests (learning)
│   ├── helm/                         # Helm charts (production)
│   ├── grafana/
│   │   └── queue-dashboard.json      # importable queue/scheduling/reaper/retry/webhook dashboard (ADR-007)
│   └── scripts/
│       ├── init-db.sql
│       └── seed-data.sql
│
├── docs/
│   ├── product/
│   │   ├── MVP.md                    # MVP scope and acceptance criteria
│   │   └── ROADMAP.md                # phase plan: lab → platform → SaaS
│   ├── api/
│   │   ├── caseflow-v1.yaml          # Caseflow external contract (OpenAPI 3.1, ADR-007 §6)
│   │   └── ci-execution.md           # GitHub Actions / GitLab CI / Jenkins snippets (ADR-007 §5.4)
│   ├── architecture/
│   │   └── decisions/                # ADRs (Architecture Decision Records)
│   │       └── 001-template.md
│   └── runbooks/
│       ├── local-dev-setup.md
│       └── repository-execution.md   # docker-proxy, digest-pin rotation, worker/worker-repo split, planted-secret check (ADR-009)
│
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                    # lint + test + build
│   │   └── deploy.yml                # deploy to Azure (later)
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── CODEOWNERS
│
└── .claude/
    ├── settings.json                 # permissions, hooks, env
    ├── rules/                        # auto-loaded guardrails (path-scoped)
    │   ├── general.md                # always loaded — universal rules
    │   ├── java-backend.md           # loaded when editing *.java
    │   ├── react-frontend.md         # loaded when editing *.ts, *.tsx
    │   ├── database-migrations.md    # loaded when editing *.sql migrations
    │   ├── docker-infra.md           # loaded when editing Dockerfiles, k8s, helm
    │   ├── ci-cd.md                  # loaded when editing .github/workflows
    │   ├── tests.md                  # loaded when editing test files
    │   ├── security.md               # loaded when editing auth/security code
    │   ├── kafka-events.md           # loaded when editing events/consumers
    │   ├── api-design.md             # loaded when editing controllers/API client
    │   └── terraform-iac.md          # loaded when editing infra/terraform/**
    ├── agents/
    │   ├── planner.md                # designs implementation plans
    │   ├── implementer.md            # writes code from plans
    │   ├── reviewer.md               # reviews code quality
    │   ├── debugger.md               # diagnoses bugs
    │   ├── architect.md              # system design decisions
    │   └── devops.md                 # CI/CD and infra concerns
    └── skills/
        ├── java-spring/
        │   └── SKILL.md              # Spring Boot patterns, modules
        ├── react-typescript/
        │   └── SKILL.md              # React + TS conventions
        ├── kafka-redis/
        │   └── SKILL.md              # event-driven + caching patterns
        ├── docker-k8s/
        │   └── SKILL.md              # containers + orchestration
        ├── api-testing/
        │   └── SKILL.md              # API + E2E test automation
        ├── system-design/
        │   └── SKILL.md              # architecture patterns, ADRs
        ├── security/
        │   └── SKILL.md              # OAuth, SSO, JWT, TLS, rate limiting, OWASP
        ├── ai-engineering/
        │   └── SKILL.md              # RAG, LangChain, agents, embeddings, vector DB
        ├── infrastructure-as-code/
        │   └── SKILL.md              # Terraform, Azure, IaC, remote state, modules
        ├── ci-cd/
        │   └── SKILL.md              # GitHub Actions, pipelines
        ├── testing/
        │   └── SKILL.md              # JUnit, Vitest, Playwright, Testcontainers
        ├── code-review/
        │   └── SKILL.md              # review guide for Java + React
        └── git-workflow/
            └── SKILL.md              # branching, commits, PRs
```

## How to run (local development)

```bash
# Prerequisites: Java 21, Node 20+, Docker Desktop

# Start infrastructure (Postgres, Redis, Kafka)
docker compose -f infra/compose/docker-compose.yml up -d

# Start backend API
cd apps/api && ./mvnw spring-boot:run

# Start worker
cd apps/worker && ./mvnw spring-boot:run

# Start gateway
cd apps/gateway && ./mvnw spring-boot:run

# Start frontend
cd apps/web && npm install && npm run dev
```

## Coding standards (apply to ALL code in this repo)

### Java (backend, worker, gateway)
- **Java 21** features: records, sealed interfaces, pattern matching, virtual threads.
- **Type safety first** — no raw types, no `Object` where a generic fits.
- **Constructor injection** only — never field injection with `@Autowired`.
- **Records for DTOs** — mutable classes only when state genuinely changes.
- **Small methods** — if a method exceeds ~30 lines, split it.
- **No `@SuppressWarnings`** without a comment explaining why.
- **Narrow exceptions** — catch the most specific exception. Never `catch (Exception e)` in business logic.
- **Logging** — use SLF4J. No `System.out.println` in production code.

### TypeScript / React (frontend)
- **Strict TypeScript** — `strict: true` in tsconfig, no `any` without justification.
- **Functional components only** — no class components.
- **Named exports** — avoid default exports (better refactoring support).
- **TanStack Query** for all server state — no manual `useEffect` + `fetch`.
- **Tailwind CSS** for styling — no CSS modules or styled-components.
- **Small components** — if a component file exceeds ~100 lines, split it.
- **Custom hooks** for reusable logic — extract early, not late.

### General (all code)
- **No secrets in code** — use environment variables, never hardcode credentials.
- **Tests alongside code** — every feature ships with tests.
- **Imports ordered** — standard lib → framework → third-party → local.
- **No dead code** — delete it, don't comment it out.
- **No premature optimization** — make it correct, then make it fast.

## Domain rules (NON-NEGOTIABLE)

1. **Multi-tenancy aware from day one.** Every entity belongs to an org/project.
   Even in single-tenant mode, include `tenant_id` / `project_id` on all tables.
2. **Test runs are immutable.** Once a run starts, its configuration is snapshotted.
   Editing a test suite does not retroactively change historical runs.
3. **Kafka events are the source of truth for execution flow.** The API publishes
   "run requested" events; workers consume them. The API does not directly invoke
   the worker.
4. **Every API endpoint is authenticated and authorized.** No public endpoints
   except health checks and login.
5. **Database migrations are versioned.** Use Flyway. Never modify a migration
   that has already been applied.
6. **Hexagonal architecture for complex modules.** Business logic depends on
   interfaces (ports), not on frameworks. Adapters implement ports. Dependency
   direction is always inward: adapters → application → domain.
7. **Security is not optional.** JWT auth from Phase 1. RBAC enforced. OWASP
   Top 10 checklist on every review. Secrets never in code. TLS in production.
8. **Rate limiting on all public APIs.** Gateway-level per-client limits via
   Redis. Application-level per-operation limits for expensive operations
   (run triggers, AI requests).
9. **Event-driven, not request-driven, for execution.** Services publish facts
   (events); other services react. No synchronous orchestration between API
   and Worker.
10. **API design is RESTful and versioned.** All endpoints under `/api/v1/`.
11. **Never handle raw card data.** All payment flows go through Stripe Checkout
    or Stripe Customer Portal. Stripe is the source of truth for billing state;
    our DB stores a synced copy via webhooks. Verify webhook signatures always.
    Consistent envelope format. Standard HTTP status codes. OpenAPI documented.

## How to work with Claude in this repo

### Subagent workflow
- For **non-trivial changes** (new feature, new service, architecture change):
  invoke the **planner** first, then the **implementer**, then the **reviewer**.
- For **system design decisions** (new module, technology choice, API design):
  invoke the **architect** subagent before planning.
- For **infrastructure changes** (Docker, CI/CD, Kubernetes, cloud):
  invoke the **devops** subagent.
- When **something is broken** and the cause is unclear:
  invoke the **debugger** for root-cause analysis.
- For **trivial edits** (typo, rename, one-line fix): just edit directly.

### Skills — when to load which
- Writing or editing **Spring Boot** code → load **java-spring** skill.
- Writing or editing **React / TypeScript** code → load **react-typescript** skill.
- Working with **Kafka or Redis** → load **kafka-redis** skill.
- Working with **Docker, Kubernetes, or Helm** → load **docker-k8s** skill.
- Writing or editing **tests** (any layer) → load **testing** skill.
- Writing or editing **API or E2E tests** → load **api-testing** skill.
- Working on **CI/CD pipelines** → load **ci-cd** skill.
- Making **architecture decisions** → load **system-design** skill.
- Working on **auth, security, OAuth, TLS, rate limiting** → load **security** skill.
- Building the **AI agent, RAG, LangChain, embeddings** → load **ai-engineering** skill.
- Working on **Terraform, Azure provisioning, IaC** → load **infrastructure-as-code** skill.
- **Reviewing code** → load **code-review** skill.
- Making **commits or PRs** → follow **git-workflow** skill.

### Architecture
- Read `ARCHITECTURE.md` before making structural changes. Update it after.
- For significant decisions, create an ADR in `docs/architecture/decisions/`.
- When adding a new module to the API, follow the existing module structure
  (controller → service → repository → DTOs → events).

### MCP integrations

MCP servers are configured in `.mcp.json` at the project root. Claude Code
reads this file automatically and connects to the listed servers on startup.

**Configured now (in `.mcp.json`):**
- **Playwright MCP** (`@playwright/mcp`) — Browser automation for E2E testing.
  Uses Playwright's accessibility tree (not screenshots) for fast, deterministic
  browser interaction. Claude can navigate pages, click, fill forms, take
  screenshots, and run test scenarios.
- **Browser MCP** (`cursor-ide-browser`) — Cursor's built-in browser for live
  testing and visual verification (configured in Cursor, not `.mcp.json`).

**Add later (when ready):**

To add a new MCP server, either edit `.mcp.json` directly or use the CLI:
```bash
claude mcp add --scope project --transport stdio <name> -- <command> [args...]
```

- **Figma MCP** — Pull design tokens, component specs, and layouts from Figma
  into React code. Add when frontend design work begins.
  ```json
  "figma": {
    "command": "cmd",
    "args": ["/c", "npx", "-y", "figma-developer-mcp"],
    "env": { "FIGMA_API_KEY": "${FIGMA_API_KEY}" }
  }
  ```
  Requires: Figma personal access token → set as `FIGMA_API_KEY` environment
  variable (never hardcode in `.mcp.json`).

- **Google Stitch** — UI design canvas; export **DESIGN.md** (design tokens +
  rationale) for `apps/web/`. Install skills when doing frontend lab work:
  ```bash
  npx skills add google-labs-code/stitch-skills --global
  ```
  Spec: https://github.com/google-labs-code/design.md — keep `apps/web/DESIGN.md`
  in sync with Tailwind theme. See ROADMAP Phase 7 “UI design with Google Stitch”.

- **GitHub MCP** — PR management, issue tracking, CI status checks.
  ```bash
  claude mcp add --transport http github https://mcp.github.com
  ```

- **PostgreSQL MCP** — Direct database queries from Claude for debugging.
  Add when database is running.
  ```json
  "postgres": {
    "command": "cmd",
    "args": ["/c", "npx", "-y", "@modelcontextprotocol/server-postgres"],
    "env": { "DATABASE_URL": "${DATABASE_URL}" }
  }
  ```

## Development phases

| Phase | Focus | What ships |
|---|---|---|
| 1 — Foundation | Project skeleton, local dev, basic CRUD | API + DB + React shell |
| 2 — Core Platform | Test catalog, run orchestration, results | Kafka + worker + dashboard |
| 3 — Intelligence | Flaky detection, AI failure analysis | Analytics + AI integration |
| 4 — SaaS Ready | Multi-tenancy, auth, onboarding | SSO (OAuth/OIDC) + 2FA (email/SMS/TOTP) |
| 4B — Payments | Stripe, subscriptions, billing | Checkout, webhooks, plan enforcement |
| 5 — Cloud Native | AKS deployment, observability | Helm + Terraform + monitoring |
| 6 — AI Agent | RAG, LangChain, vector DB, tool-use agents | Python AI service + agent UI |
| 7 — Playground | Lab: patterns, k6 load tests, Stitch DESIGN.md | See ROADMAP Phase 7 |

See `docs/product/ROADMAP.md` for detailed phase plans.
