# Architecture

This document describes the system design of QualityOps Lab, the key
decisions, and the reasoning behind them. Keep it updated as the project evolves.

## System overview

```
                          ┌─────────────┐
                          │   Browser    │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │   React     │
                          │   Frontend  │
                          │  (Vite/TS)  │
                          └──────┬──────┘
                                 │ HTTP/WS
                          ┌──────▼──────┐
                          │   Spring    │
                          │   Cloud     │
                          │   Gateway   │
                          └──────┬──────┘
                                 │ routes to
              ┌──────────────────┼──────────────────┐
              │                  │                   │
       ┌──────▼──────┐   ┌──────▼──────┐    ┌──────▼──────┐
       │  API Server  │   │  API Server  │    │   Static    │
       │  (Spring     │   │  (replica)   │    │   Assets    │
       │   Boot)      │   │              │    │             │
       └──────┬──────┘   └──────┬──────┘    └─────────────┘
              │                  │
              ├──────────────────┘
              │
    ┌─────────┼──────────┬──────────────┐
    │         │          │              │
┌────────┐ ┌──────┐ ┌────────┐        ┌─────────────────────────┐
│Postgres│ │Redis │ │ Kafka  │◄──────►│ Worker                  │
│  (DB)  │ │(cache│ │(events)│ runs.* │ qualityops-worker       │
│        │ │ +pub)│ │        │        │ real API runner +       │
│        │ │      │ │        │        │ real browser runner     │
│        │ │      │ │        │        │ (Playwright) + SSRF      │
│        │ │      │ │        │        │ guard; own "worker" schema│
└────────┘ └──────┘ └────────┘        └─────────────────────────┘
   ▲ all *authoritative* DB writes are API-only. Worker owns only worker.execution_attempt.
   Real API runner (HTTP) + SSRF guard: Phase 2B1. Real declarative browser runner
   (embedded Playwright for Java, fresh BrowserContext per execution): Phase 2B2 (ADR-004).
   Perf runners: later.
```

## Architectural style: Hexagonal (Ports and Adapters)

The backend follows **hexagonal architecture** (ports and adapters). Business
logic lives at the center and has no dependency on frameworks, databases, or
messaging. External concerns plug in through interfaces (ports) and their
implementations (adapters).

```
                    ┌─────────────────────────────────┐
                    │         Driving Adapters         │
                    │  (things that call our code)     │
                    │                                  │
                    │  REST Controllers                │
                    │  Kafka Consumers                 │
                    │  Scheduled Jobs                  │
                    │  CLI / Test Harness              │
                    └──────────┬──────────────────────┘
                               │ calls
                    ┌──────────▼──────────────────────┐
                    │       Input Ports                │
                    │  (use case interfaces)           │
                    │                                  │
                    │  TriggerRunUseCase               │
                    │  CreateProjectUseCase            │
                    │  GetResultsUseCase               │
                    └──────────┬──────────────────────┘
                               │ implements
                    ┌──────────▼──────────────────────┐
                    │     Domain / Business Logic      │
                    │  (pure Java, no framework deps)  │
                    │                                  │
                    │  Domain entities                 │
                    │  Domain services                 │
                    │  Domain events                   │
                    │  Validation rules                │
                    └──────────┬──────────────────────┘
                               │ depends on
                    ┌──────────▼──────────────────────┐
                    │       Output Ports               │
                    │  (repository / gateway intf.)    │
                    │                                  │
                    │  ProjectRepository (interface)   │
                    │  EventPublisher (interface)      │
                    │  RunStatusCache (interface)      │
                    │  NotificationGateway (interface) │
                    └──────────┬──────────────────────┘
                               │ implemented by
                    ┌──────────▼──────────────────────┐
                    │       Driven Adapters            │
                    │  (infrastructure implementations)│
                    │                                  │
                    │  JPA Repository (Postgres)       │
                    │  Kafka Producer                  │
                    │  Redis Cache                     │
                    │  REST Client (external APIs)     │
                    │  SMTP (email notifications)      │
                    └─────────────────────────────────┘
```

### Why hexagonal?

- **Testability** — Business logic can be tested without Spring, Kafka, or Postgres.
  Inject mock adapters and test pure domain behavior.
- **Flexibility** — Swap Postgres for DynamoDB, Kafka for RabbitMQ, or Redis for
  Memcached without touching business logic.
- **Clarity** — Forces you to think about what is domain logic vs. what is
  infrastructure glue. Controllers and repositories are thin adapters.
- **Learning** — This is a lab project. Hexagonal architecture is one of the
  most interview-relevant patterns for senior engineers.

### Practical application (not academic purity)

We're pragmatic, not dogmatic:
- **Start simple.** In Phase 1, services can call repositories directly.
  Extract ports/adapters when a module is complex enough to justify it.
- **Spring is allowed.** The domain layer can use Spring annotations like
  `@Service` and `@Transactional`. Pure hexagonal says no framework in the
  domain, but for a lab project the overhead of a pure approach isn't worth it.
- **Extract when it hurts.** If you find yourself mocking 5 things to test
  one service, that's a sign to extract a port.

## Domain modules

The API is a **modular monolith** — each domain is a separate Java package
with clear boundaries. Modules communicate through internal service calls
now, and can be extracted to microservices later if needed.

```
com.qualityops.api
├── identity/        ← auth, users, roles, orgs, API tokens
├── project/         ← projects, workspaces
├── environment/     ← environment registry + scheduled health probe (ADR-008)
├── testsuite/       ← test catalog: suites, cases, tags, ownership
├── execution/       ← run orchestration, queue dispatch, stuck-run reaper, queue-driven retry, CI API
├── scheduling/      ← ShedLock scheduler, run_queue + health-check maintenance (ADR-006/007/008)
├── webhook/         ← outbound signed run-completion webhooks (ADR-007)
├── realtime/        ← STOMP run-progress WebSocket + Redis pub/sub fan-out (ADR-008)
├── audit/           ← @Audited/@Timed AOP aspects → audit_log (ADR-008)
├── result/          ← results, analytics (flaky/trends/slow), flakiness scoring
├── testdata/        ← test data management, seed sets, generators
├── mock/            ← dependency virtualization, response replay
├── ai/              ← AI assistant: failure analysis, test generation
└── config/          ← Spring config, security, Kafka, Redis, Flyway
```

The **Worker** (`apps/worker`, package `com.qualityops.worker.execution`) is a
hexagonal-lite slice: Kafka in-adapter → application service → Kafka out-adapter,
with **no persistence adapter** (it has no datasource).

### Module structure — hexagonal layout

Each module follows the ports-and-adapters layout:

```
execution/
├── adapter/
│   ├── in/
│   │   ├── web/
│   │   │   └── RunController.java          # REST adapter (driving)
│   │   └── messaging/
│   │       └── RunCompletedConsumer.java    # Kafka adapter (driving)
│   └── out/
│       ├── persistence/
│       │   ├── RunJpaRepository.java        # JPA adapter (driven)
│       │   └── RunEntity.java               # JPA entity (infra, not domain)
│       ├── messaging/
│       │   └── RunKafkaPublisher.java       # Kafka adapter (driven)
│       └── cache/
│           └── RunRedisCache.java           # Redis adapter (driven)
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── TriggerRunUseCase.java       # input port (interface)
│   │   └── out/
│   │       ├── RunRepository.java           # output port (interface)
│   │       ├── RunEventPublisher.java       # output port (interface)
│   │       └── RunStatusCache.java          # output port (interface)
│   └── service/
│       └── RunService.java                  # implements use cases
├── domain/
│   ├── TestRun.java                         # domain entity (pure Java)
│   ├── RunStatus.java                       # value object / enum
│   └── RunPolicy.java                       # domain rules
├── dto/
│   ├── CreateRunRequest.java                # API request record
│   └── RunResponse.java                     # API response record
└── exception/
    └── RunNotFoundException.java
```

**Simplified structure for smaller modules** (Phase 1):

Not every module needs the full hexagonal layout. For simple CRUD modules
(project, environment), a simpler structure is fine:

```
project/
├── ProjectController.java      # REST endpoints
├── ProjectService.java         # business logic
├── ProjectRepository.java      # Spring Data JPA interface
├── dto/
│   ├── CreateProjectRequest.java
│   └── ProjectResponse.java
├── model/
│   └── Project.java            # JPA entity
└── exception/
    └── ProjectNotFoundException.java
```

**Rule:** Start simple. Upgrade to full hexagonal when the module has
multiple adapters (Kafka + REST + cache) or complex domain logic.

## Data model (core entities)

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ Organization │────<│   Project    │────<│ Environment  │
│              │     │              │     │              │
│ id           │     │ id           │     │ id           │
│ name         │     │ org_id (FK)  │     │ project_id   │
│ slug         │     │ name         │     │ name         │
│ created_at   │     │ description  │     │ url          │
└──────┬───────┘     └──────┬───────┘     │ status       │
       │                    │              │ version      │
       │             ┌──────▼───────┐     └──────────────┘
       │             │  TestSuite   │
       │             │              │
       │             │ id           │
       │             │ project_id   │
       │             │ name         │
       │             │ type (API/   │
       │             │   UI/PERF)   │
       │             │ tags[]       │
       │             └──────┬───────┘
       │                    │
┌──────▼───────┐     ┌──────▼───────┐     ┌──────────────┐
│    User      │     │  TestCase    │     │   TestRun    │
│              │     │              │     │              │
│ id           │     │ id           │     │ id           │
│ org_id (FK)  │     │ suite_id     │     │ project_id   │
│ email        │     │ name         │     │ suite_id     │
│ role         │     │ description  │     │ env_id       │
│ api_token    │     │ priority     │     │ status       │
└──────────────┘     │ automated    │     │ triggered_by │
                     └──────────────┘     │ started_at   │
                                          │ finished_at  │
                                          └──────┬───────┘
                                                 │
                                          ┌──────▼───────┐
                                          │ TestResult   │
                                          │              │
                                          │ id           │
                                          │ run_id       │
                                          │ case_id      │
                                          │ status       │
                                          │ duration_ms  │
                                          │ error_msg    │
                                          │ screenshot   │
                                          │ retry_count  │
                                          │ flaky_score  │
                                          └──────────────┘

┌──────────────┐     ┌──────────────┐
│ Subscription │     │   Invoice    │
│              │     │              │
│ id           │     │ id           │
│ org_id (FK)  │     │ sub_id (FK)  │
│ stripe_cust  │     │ stripe_inv   │
│ stripe_sub   │     │ amount       │
│ plan (FREE/  │     │ currency     │
│  PRO/ENTERP) │     │ status       │
│ status       │     │ period_start │
│ current_per  │     │ period_end   │
│ runs_used    │     │ pdf_url      │
│ runs_limit   │     │ created_at   │
└──────────────┘     └──────────────┘
```

**Immutable run snapshot on the wire.** The frozen `config_snapshot` also travels
inside `RunRequestedEvent` and `RunCompletedEvent`, so the stateless Worker and
the result generator score a run against exactly what it ran — never against the
suite's current (possibly since-edited) cases.

**`test_runs.execution_id`** (V8) — `NOT NULL UNIQUE`; the execution-attempt
identity matched (with `org_id`) on every lifecycle transition. A stale or
foreign `execution_id` ⇒ 0-row no-op. **`test_cases.api_request`** (V9) —
nullable JSONB API-request spec (method, URL, headers, body, expected status,
timeout, response-size cap, assertions). **`worker.execution_attempt`** lives in
a separate `worker` schema with its own Flyway history and is **not** authoritative
state — a durable side-effect / dedup guard only (ADR-003).

**Phase 2C tables (V12–V15, ADR-006):**
- **`shedlock`** (V12) — ShedLock coordination row per `@Scheduled` job. **No
  `org_id`** — a documented infra-coordination exception, like
  `flyway_schema_history`. No JPA entity (ShedLock uses `JdbcTemplate`).
- **`run_queue`** (V13) — 1:1 with `test_runs` (`run_id UNIQUE`). `priority` /
  `queue_state` are `VARCHAR + CHECK` (not PG enum). `requested_event_json` (JSONB,
  nullable) is the frozen `RunRequestedEvent` mini-outbox, nulled on any terminal
  transition — including the dispatcher's rollback-to-CANCELLED branch. Partial
  indexes drive the dispatch scan (`WHERE queue_state='QUEUED'`)
  and the per-org active count (`WHERE queue_state IN ('DISPATCHED','RUNNING')`).
- **`schedule`** (V14) — the `Schedule` aggregate; `kind` / `priority` /
  `catch_up_policy` are `VARCHAR + CHECK`; `next_fire_at` is a materialised
  absolute instant so the tick query is a pure indexed range scan.
- **`schedule_fire`** (V14) — per-occurrence dedup ledger, `UNIQUE (schedule_id,
  fire_slot)`, `ON DELETE CASCADE` from `schedule`.
- **`org_run_concurrency`** (V15) — per-org max-active-runs override
  (`max_active_runs > 0`). 2C ships the table + read path + a global default only;
  2D adds the write path (`PUT|GET /api/v1/admin/orgs/{orgId}/run-concurrency`) —
  no migration.

**Phase 2D tables (V16–V18, ADR-007):**
- **`run_queue.retry_of`** (V16) — nullable FK → `run_queue(run_id)` linking a
  retry run to its original; **`run_queue.retry_count`** (`INT NOT NULL DEFAULT 0`)
  is monotone along the retry chain. Two partial indexes (`WHERE retry_of IS NOT
  NULL`) support the per-org rolling-window `COUNT` and chain lookups. No `org_id`
  on the new columns — the row already carries it.
- **`ci_idempotency_key`** (V17) — CI idempotency dedup, `UNIQUE (org_id,
  idempotency_key)` is the concurrent-first-call arbiter; `run_id` FK → `test_runs`;
  `request_fingerprint VARCHAR(64)`. Pruned by `QueueMaintenanceService` after
  `qualityops.ci.idempotency-retention` (P7D).
- **`webhook_endpoint`** (V18) — org-scoped registered endpoints; `project_id`
  nullable (NULL ⇒ all runs in the org); `secret` is **plaintext at rest in 2D**
  (masked as `secretSet:true` over the API; column encryption / Key-Vault is a
  Phase-4 hardening). Partial index `(org_id, project_id) WHERE enabled`.
- **`webhook_delivery`** (V18) — durable outbox, `state VARCHAR + CHECK` (not a PG
  enum — states will churn), `UNIQUE (run_id, webhook_endpoint_id)` makes a
  redelivered terminal a no-op INSERT, `payload_json` frozen at enqueue for
  signature stability, `next_attempt_at` + partial due-index drive the
  `FOR UPDATE SKIP LOCKED` sender scan.

## Key design decisions

### 1. Modular monolith over microservices

**Decision:** Start as a single Spring Boot application with well-defined
module boundaries. Each domain (identity, testsuite, execution, etc.) is
a separate package with its own controller, service, repository, and DTOs.

**Why:** Microservices add operational complexity (service discovery, distributed
tracing, network failures) without proportional benefit at this scale. Module
boundaries give us the same logical separation with simpler deployment.

**Progression:**
```
Phase 1-2: One Spring Boot app (API + Kafka consumers together)
           └── com.qualityops.api
               ├── project/        ← REST controllers
               ├── testsuite/      ← REST controllers
               ├── execution/      ← REST controllers + Kafka consumers (same app)
               └── config/         ← Kafka config, security, etc.

Phase 2A+: Split performed (ADR-002)
           ├── packages/shared-events/ ← Kafka event contract records (com.qualityops.events)
           ├── apps/api/               ← REST + run lifecycle/result back-consumers; sole DB writer
           └── apps/worker/            ← runs.requested consumer + simulated execution; NO datasource

Phase 2B1 (ADR-003): the Worker gains a datasource scoped to a dedicated "worker"
           schema (one table, its own Flyway stream) — a durable execution-attempt
           ledger. Still not an authoritative writer.
```

**Phase 2A (done — see ADR-002):** the split is performed. `RunRequestedConsumer`
and the simulated execution move to `apps/worker` (package `com.qualityops.worker`),
a **stateless** Spring Boot app with **no datasource** — it never touches Postgres.
The API keeps `RunKafkaPublisher`, gains lifecycle back-consumers on
`runs.started` / `runs.completed` / `runs.failed` (group `api-execution`), and
keeps the result-generating `RunCompletedConsumer` (group `api-results`). The API
remains the sole database writer. Event records live in `packages/shared-events`.

**When to split:** When you notice that long-running test execution blocks
API responsiveness, or when you want to scale consumers independently, or
simply when you're ready to learn the extraction process. Done in Phase 2A
(ADR-002): the `runs.requested` `@KafkaListener` and the execution logic moved
to a new Spring Boot app pointed at the same Kafka — but **not** the database;
the Worker is stateless and the API stays the sole DB writer (see decision #2).

**Revisit when:** A module needs independent scaling (e.g., the worker needs
10x instances while the API stays at 2), or teams form around specific modules.

### 2. Kafka for execution orchestration

**Decision:** Test runs flow through Kafka events, not direct API-to-worker calls.
Phase 1 ran every Kafka consumer in-process inside the API app. Phase 2A
(ADR-002) moved the `runs.requested` consumer and the execution logic into a
separate database-free `apps/worker`; the API keeps the run-lifecycle and
result consumers and remains the only writer of run/result state.

**Why:**
- Decouples the API from the worker — even in the same app, they communicate via events, not method calls.
- Natural retry semantics with dead-letter topics.
- Event history for auditing and replay.
- Prepares for future event-sourcing of execution state.
- When you split the worker out later, **zero code changes** to producers — they already publish to Kafka, not call methods directly.

**Event flow (Phase 2B2):**
```
API   publishes → runs.requested   (frozen snapshot embedded; key = runId; v3 carries an
                                    optional ApiRequestSnapshot AND an optional
                                    BrowserTestSnapshot per case; carries execution_id)
Worker consumes → runs.requested   (group worker-execution)
Worker CLAIMs   → worker.execution_attempt (INSERT … ON CONFLICT); a COMPLETED claim ⇒
                 re-emit the cached terminal and stop
Worker selects  → a runner per case (browserTest present ⇒ declarative Playwright scenario in a
                 fresh BrowserContext + SSRF guard; else apiRequest present ⇒ real HTTP + SSRF
                 guard; else simulated)
Worker (per case) → in-run retry loop (transient TIMEOUT/ERROR + SideEffectClass NONE_OBSERVED +
                 budget room); then best-effort artifact upload (≤10s, never fatal) →
                 ArtifactStoragePort.put → private MinIO bucket, org-first key
Worker publishes → runs.started
Worker publishes → results.chunk  (one per case: verdict + attemptEpoch + ArtifactReference[])
Worker publishes → runs.completed  (v4: outcome + snapshot + per-case summary now carrying
                 attemptEpoch + artifacts[])
              or → runs.failed     (interrupt / harness fault only; generic redaction-safe reason)
API   consumes → runs.started/completed/failed  (group api-execution) → conditional status UPDATE,
                 matched on org_id AND execution_id (stale/foreign ⇒ 0-row no-op)
API   consumes → results.chunk  +  runs.completed  (group api-results) → the SAME org- and
                 executionId-guarded, epoch-monotone upsert into test_results + test_result_artifacts
                 (a lost chunk is reconciled by the v4 terminal; legacy fabrication only for v1/simulated)
```
WebSocket push of `results.chunk` to the dashboard and queue-driven retry remain Phase 2E / 2C.

**As implemented in Phase 2A:** the API's `RunService.trigger` persists the run
PENDING, then publishes a self-contained `RunRequestedEvent` carrying the frozen
test-case snapshot. The database-free Worker (`apps/worker`) consumes it, runs the
simulated execution (sleep 200–500 ms on a virtual thread; 80% PASSED / 20%
FAILED), and publishes `runs.started`, then exactly one of `runs.completed`
(terminal outcome + snapshot) or `runs.failed` (execution error). The API's
`RunLifecycleConsumer` applies conditional status UPDATEs and its
`RunCompletedConsumer` generates one `test_results` row per snapshot case. All
Phase-1 idempotency mechanisms are preserved (conditional UPDATE, `existsByRunId`,
`uq_test_results_run_case`). `result.chunk` streaming needs real per-case
execution — Phase 2B2.

**As refined in Phase 2B1 (ADR-003):** a case carrying an `ApiRequestSnapshot`
runs a real outbound HTTP request via `ApiExecutionRunner` (JDK `HttpClient`) —
SSRF-validated (all resolved IPs denylist-checked; redirects off; loopback/
link-level/metadata blocked, dev allowlist for private hosts), request/response
metadata and the truncated response sample redacted, response memory bounded to
`maxResponseBytes`, per-request timeout + cooperative cancellation. A case with
no snapshot still runs in `SimulatedExecutionRunner`. Duplicate delivery — even
across a Worker restart — no longer double-fires the HTTP call: the Worker durably
CLAIMs each attempt in `worker.execution_attempt` and, on a redelivered
COMPLETED attempt, re-emits the cached terminal event. Every API lifecycle
transition and result write is additionally guarded by `execution_id`.

**As refined in Phase 2B2 (ADR-004):** a case carrying a `BrowserTestSnapshot`
runs a **declarative browser scenario** (navigate / click / fill / select /
press-key steps; text / URL / visibility / element-state assertions; stable
selectors preferring role, label and test-id) against a real Chromium via
**embedded Playwright for Java**, confined to a single-thread executor, in a
**fresh `BrowserContext` per execution**. There is no user-supplied JavaScript or
shell in a test definition. `startUrl` and every `NAVIGATE` URL are SSRF-validated
with the same `TargetValidator`, and (by default) private/loopback/metadata
**sub-resources** are intercepted and aborted. `page` → tracing → `context` are
closed in a guarded `finally`; a hard-timeout path additionally `cancel(true)`s
the future and force-recycles the shared `Browser`. Screenshots (on failure) and
traces are captured to a temp dir, then (Phase 2B3) staged and uploaded
best-effort to a private MinIO bucket via `ArtifactStoragePort`; the API presigns
short-TTL GET URLs with a **separate read-only** credential. `RunRequestedEvent` /
`RunCompletedEvent` are `SCHEMA_VERSION = 4`, wire-compatible with v1–v3
(`browserTest` / `secretRef` / `attemptEpoch` deserialise to null/0). The Worker
writes nothing to Postgres but `worker.execution_attempt`; the object store is a
separate write-only capability.

### Phase 2B3 — durable artifacts, per-case streaming, retry, `secretRef` (ADR-005)

`ArtifactStoragePort` (Worker output port) + `S3ArtifactStorage` (MinIO Java
client; Azure Blob is a Phase-5 adapter). **Object storage (test artifacts):**
one private bucket, org-first path-addressed keys, SSE-S3, a retention lifecycle
rule; the Worker holds a write-only key, the API a read-only key and only ever
presigns GET (`GET /api/v1/runs/{id}/artifacts`, `GET /api/v1/artifacts/{id}`).
Upload is synchronous, per-case, 10s-bounded, and can never delay or fail a
terminal event — failure ⇒ `ArtifactReference` status `UNAVAILABLE`.
**`results.chunk`** is a per-case streaming topic (key = runId, group
`api-results`); one `ResultChunkEvent` per case. Both the chunk and the v4
terminal drive the same org- + `executionId`-guarded, epoch-monotone upsert into
`test_results` (new `attempt_epoch` column, V11) and `test_result_artifacts`
(new table, V11) — losing every chunk is corrected by the terminal.
**Bounded in-run retry** re-runs a transient `TIMEOUT`/`ERROR` (never `FAILED` /
`BLOCKED` / after a seen response status / after an interactive browser step) with
budget room; `SideEffectClass` is worker-internal and never serialised.
**`secretRef`** (`HttpHeader.secretRef` / `BrowserStep.secretValue`, key
`[A-Z0-9_]{1,64}`) is resolved at execution time by the Worker
(`EnvFileSecretResolver`; Key Vault is Phase 5); the plaintext never enters an
event, `config_snapshot`, a log, `test_results`, or (by default) an artifact —
secret-sourced headers are always masked, secret-bearing screenshots are gated
(`upload-secret-cases`, default false) with input masking + forced trace-off, and
an unresolvable `secretRef` ⇒ case `BLOCKED`.

### Phase 2C — scheduling & queue (ADR-006)

A new hexagonal `scheduling` module in `apps/api` (`com.qualityops.api.scheduling`)
owns a `Schedule` aggregate: one-time (`fire_at`) and recurring (6-field Spring
cron + IANA time zone, DST-correct via `CronCalculator`), with pause/resume,
`SKIP_MISSED` / `FIRE_ONCE` catch-up, a materialised absolute `next_fire_at`, and
a live next-fires preview. Endpoints: `GET|POST /api/v1/projects/{projectId}/schedules`,
`GET|PUT|DELETE /api/v1/schedules/{id}`, `POST …/{id}/pause`, `POST …/{id}/resume`,
`GET …/{id}/next-fires?count=`. `priority = HIGH` is gated to OWNER/ADMIN by a
body-aware SpEL guard.

**`trigger` no longer publishes.** `RunService.trigger` and a fired schedule both
call the shared `EnqueueRunUseCase`, which in one transaction validates the
target, freezes the snapshot, mints `executionId`, inserts `test_runs` PENDING and
a `run_queue` row `QUEUED` with the **fully-serialised `RunRequestedEvent`** frozen
in `requested_event_json` (a single-purpose mini-outbox) — and publishes nothing.

**Leader coordination:** `net.javacrumbs.shedlock` (spring + jdbc-template
provider) backed by the `shedlock` table (V12, no `org_id` — a documented infra
exception, like `flyway_schema_history`). `@EnableScheduling` +
`@EnableSchedulerLock`, `.usingDbTime()`. Two `@Scheduled` beans, two global lock
names: `ScheduleTickJob` (`scheduling-tick`, ~15s) scans `next_fire_at` and calls
`ScheduleFireService.fire(...)` per due schedule; `QueueDispatchJob`
(`queue-dispatch`, ~2s) calls `QueueDispatchService.dispatchAvailable()`. A
lock-store outage degrades to "nothing progresses", never "fires/dispatches
twice".

**Occurrence guard:** `ScheduleFireService.fire` inserts
`schedule_fire (schedule_id, fire_slot)` `ON CONFLICT DO NOTHING`; a 0-row insert
means the occurrence already fired (retried tick, replica race, clock skew) ⇒ skip
`enqueue`, still advance `next_fire_at`. So a schedule fires **at most once per
logical occurrence** across any number of replicas and retried ticks.

**Dispatcher:** counts active runs per org, loads `org_run_concurrency` overrides
(read path only in 2C; default `max-active-runs-per-org` = 5), selects `QUEUED`
candidates ordered by an **aged effective priority** (anti-starvation) with
`FOR UPDATE SKIP LOCKED`, then per row **claims (conditional `UPDATE` on
`queue_state='QUEUED'`, committed) and only then publishes `runs.requested`
synchronously**. A lost send rolls the row back to `QUEUED` (or to `FAILED` at
`dispatch-max-attempts`). Claim-then-publish means a concurrent
`POST /runs/{id}/cancel` in the window sees `DISPATCHED` and takes the cooperative
path. On a failed publish the `run_queue` terminal/rollback write and the matching
`test_runs` `PENDING→FAILED` / `PENDING→CANCELLED` UPDATE (`requested_event_json`
nulled) are **reconciled atomically in one `TransactionTemplate` unit**
(`markDispatchFailed` for a corrupt frozen event or the attempts ceiling; the
rollback-to-CANCELLED branch when a cancel was requested in the send window), so
only the crash-stranded `DISPATCHED`+`PENDING` row (claim committed, `send()`
never reached) remains for the 2D reaper.

**Queue lifecycle:** `RunLifecycleService` advances `run_queue`
(`DISPATCHED→RUNNING→COMPLETED|FAILED`, `terminal_at` set, `requested_event_json`
nulled) **only when** the existing org- + `executionId`-guarded `test_runs`
`UPDATE` moved a row — no new guard column, redelivery-safe. A `QueueMaintenanceService`
`@Scheduled` prune trims terminal `run_queue` rows (90d) and `schedule_fire`
rows (30d).

**Cancellation:** `POST /api/v1/runs/{id}/cancel` (OWNER/ADMIN/MEMBER). The
handler does a **plain read** of the `run_queue` row plus guarded conditional
UPDATEs with a fall-through re-read (race-correct without a `FOR UPDATE` lock).
A run cancelled while `QUEUED` is set `CANCELLED` in both tables (one atomic
`TransactionTemplate` unit) with **no Kafka and no Worker** — the dispatcher's
`WHERE queue_state='QUEUED'` provably never picks it (`200`). A
`DISPATCHED`/`RUNNING` cancel is **cooperative** (`202`): the guarded
`cancel_requested=true` UPDATE commits first, then `RunCancelRequestedEvent`
(standalone, outside the `RunEvent` seal; `SCHEMA_VERSION = 1`) is published
**after commit** on the new `runs.cancel` topic (`runs.cancel.DLT`). The Worker consumes it (group `worker-execution`) into a
bounded in-memory `CancellationRegistry` keyed by `executionId` — **no `run_queue`
access, no Worker migration**. A pre-start cancel ⇒ claim + `runs.failed`; a
mid-run cancel ⇒ remaining cases `ERROR "run cancelled"`, run still completes
(aggregate `FAILED`). `CANCELLED` on `test_runs` is reserved for never-executed
runs.

**Observability:** `micrometer-registry-prometheus`; `management` exposes
`metrics,prometheus`. Meters (no `org` tag): `qualityops.queue.depth{priority}`,
`oldest_age_seconds`, `wait_seconds`, `dispatch_throughput`, `active_runs`,
`cancellations{phase}`, `schedule.fires{outcome}`, `tick_duration` /
`dispatch_duration`, `scheduling.leader{job}`, `qualityops.queue.dispatch_failed{reason}`
(`reason ∈ {attempts_ceiling, corrupt_event}`).

Migrations **V12–V15** (`shedlock`, `run_queue`, `schedule` + `schedule_fire`,
`org_run_concurrency`); `priority` / `queue_state` / `kind` / `catch_up_policy`
are `VARCHAR + CHECK`, not PG enum. No `run_status` change; no Worker migration.
For the multi-replica leader smoke, `docker-compose.dev.yml` adds a non-routed
second API replica `api-2` (host `8082`).

### Phase 2E — analytics, real-time dashboard, AOP, hardening (ADR-008)

All Phase 2E work is a read path, a transparent optimisation, an edge guard, a
cross-cutting observer, or CI/config. **No `shared-events` change, no Worker
change, no new Kafka topic.** Three first-party starters
(`spring-boot-starter-{aop,websocket,cache}`) and the `org.owasp:dependency-check-maven`
plugin (behind a `security-scan` Maven profile) are added.

- **Analytics** (`result` module, `AnalyticsController`) — `GET /api/v1/analytics/{flaky,trends,slow}`.
  Flakiness per `test_case_id` = `transitions ÷ (runsAnalyzed − 1)` over the last
  `window` `PASSED`/`FAILED` results (alternating ⇒ ~1.0; all-pass or all-fail ⇒
  0.0); stability = `1 − flakiness`. Trends = daily run pass/fail + `AVG`/`percentile_cont(0.95)`
  case duration, zero-filled. Slow = top-`limit` `test_case_id` by p95
  `test_results.duration_ms`. Three native window/aggregate queries, org- +
  project-scoped, **no materialised stats table** (`V19` = three `test_results`
  indexes). All three are `@Cacheable` (§ cache below).
- **Environment health** — a fifth leader-elected `@Scheduled` job
  (`environment-health-probe`, ShedLock, gated on `qualityops.scheduling.jobs-enabled`)
  probes `type IN ('STAGING','PRODUCTION')` env `base_url`s (JDK `HttpClient`,
  `followRedirects(NEVER)`, `probe-timeout` PT5S, body discarded) and classifies
  `HEALTHY` (2xx/3xx) / `DEGRADED` (`consecutive_failures ≥ degraded-after` 1) /
  `DOWN` (`≥ failure-threshold` 3). New `environments.health_status`
  (`VARCHAR(16) + CHECK`, **distinct from** the admin `environment_status` PG
  enum) + `last_probe_at` / `last_healthy_at` / `consecutive_failures`, and an
  `environment_health_check` history table (**V20**, `org_id NOT NULL`, pruned by
  `QueueMaintenanceService.prune()` at `history-retention` P14D). The probe's
  network I/O runs **outside** any DB transaction (only the guarded read-then-write
  pair is transactional). `common/net/OutboundAddressGuard` — the
  `WebhookUrlValidator` denylist (loopback / link-local / `169.254.169.254` /
  CGNAT / ULA / broadcast / any-local always denied; `allowPrivate` relaxes only
  site-local/CGNAT/ULA/`0.0.0.0-8`) extracted and shared. `GET /api/v1/environments/{id}/health`.
- **Redis dashboard cache** — `@EnableCaching` + `RedisCacheManager` (30 s TTL,
  `computePrefixWith(name -> name + "::")` so a key is `<cache>::<orgId>:…` —
  tenant-partitioned by construction). Caches `analytics.{flaky,trends,slow}` +
  `runs.list`. A `LoggingCacheErrorHandler` **fails open** to Postgres on any
  Redis error (`qualityops.cache.errors`). `DashboardCacheInvalidator.evictForOrg`
  (in `config` — no `execution ↔ result` cycle) `SCAN`s and deletes
  `*::<orgId>:*` from `RunLifecycleService` after a terminal transition moved a
  row; self-swallowing.
- **Real-time (`realtime` module)** — STOMP-over-SockJS `/ws` (HTTP handshake
  `permitAll`; JWT validated on the STOMP `CONNECT`; a `SUBSCRIBE` to
  `/topic/runs/{runId}` is checked against the caller's org via `GetRunUseCase` —
  the socket's tenant boundary), in-memory simple broker on `/topic`, hard
  send-buffer / send-time / message-size limits (backpressure). The existing
  `api-execution` (`runs.started|completed|failed`) and `api-results`
  (`results.chunk`) handlers push a lightweight `RunProgressEvent` through a new
  `RunProgressNotifier` output port (defined in `execution`) — **best-effort,
  never rolls back a consumer tx**. `StompRunProgressNotifier` publishes JSON to
  the `qualityops:ws:runs` Redis channel; a `RedisRunEventBridge`
  (`RedisMessageListenerContainer`, one per replica) re-broadcasts to local STOMP
  sessions, so all replicas' clients see every update; a Redis-publish failure
  degrades to local-only. The gateway gains a `/ws/**` route with **no**
  `RequestRateLimiter`.
- **Application-level rate limiting** — `@RateLimited(operation, limit, window)` +
  a Spring MVC `HandlerInterceptor` (chosen over an aspect: it can set response
  headers and is immune to the AOP self-invocation limitation) on
  `POST /api/v1/runs` (`run.trigger`, 60/h) and `POST /api/v1/ci/runs`
  (`ci.run`, 120/h). Redis fixed-window `INCR`+`PEXPIRE` per
  `ratelimit:{orgId}:{operation}:{window}`; over-limit ⇒ `429 RATE_LIMITED` +
  `Retry-After` + `X-RateLimit-{Limit,Remaining,Reset}`. **Fails open** on a Redis
  error (`qualityops.ratelimit.errors`). This is the *application-level* tier of
  decision #10 — the gateway's per-IP `RequestRateLimiter` is unchanged.
- **Cross-cutting AOP (`audit` module)** — `@Audited(action, targetType)` →
  `AuditAspect` (`@Order(10)`, inner) writes an `audit_log` row (**V21**,
  `org_id NOT NULL`, `outcome VARCHAR + CHECK`) via `AuditRecorder`
  (`Propagation.REQUIRES_NEW` + swallow-and-log `DataAccessException`, so an
  audit-write failure never breaks or rolls back the business call; the trade —
  a `SUCCESS` row can outlive a later business rollback — is documented). The
  aspect rethrows the original exception unchanged on failure; `detail` JSON is
  built with Jackson. `@Timed(value, slowThresholdMillis)` → `TimingAspect`
  (`@Order(0)`, outermost) records `qualityops.slow_op{op}` and, past the
  threshold (annotation value, else `qualityops.timing.slow-threshold-ms`),
  `qualityops.slow_op.exceeded{op}` + a WARN. `@Audited` on
  `OrgConcurrencyService.set`, `EnvironmentService.{create,update,delete}`,
  `ProjectService.delete`, `TestSuiteService.delete`,
  `WebhookEndpointService.{register,delete}`; `@Timed` on `RunService.trigger`.
  **Self-invocation limitation:** Spring AOP proxies only intercept calls that
  enter a bean from outside; `this.other()` bypasses the proxy and the annotation
  is silently ignored. Stance: annotate only the outermost proxied entry point,
  never a `private` or internally-only-called method; extract a bean if an inner
  step must be audited; do not use `AopContext.currentProxy()`. Pinned by
  `AopSelfInvocationTest` and a rule in `.claude/rules/java-backend.md`.
- **HTTPS in staging** — config + docs only (k8s/Helm ingress TLS is Phase 5).
  The recommended path terminates TLS at the LB/ingress with the gateway on plain
  HTTP on the pod network (`GATEWAY_TLS_ENABLED=false`).
  `apps/gateway/src/main/resources/application-staging.yml` enables `server.ssl.*`
  from environment variables only (no keystore is committed). HSTS is unchanged
  (already emitted by the gateway). `docs/runbooks/https-staging.md`;
  `GatewayStagingProfileIT` proves the profile boots.
- **CI security scanning** — a `security-scan` GitHub Actions job: OWASP
  Dependency-Check (`mvn -Psecurity-scan verify`, `failBuildOnCVSS=7`, SARIF) +
  Trivy image scans of api/worker/gateway (`HIGH,CRITICAL`, `exit-code 1`,
  `ignore-unfixed`, SARIF → code scanning). `npm audit --audit-level=high
  --omit=dev` in the `web` job. Suppressions are time-boxed and `CODEOWNERS`-guarded
  (`.github/dependency-check-suppressions.xml`, `.trivyignore`) — no `|| true`, no
  severity downgrade. `docs/runbooks/security-scanning.md` documents the
  planted-vulnerable-dependency exit check. Baseline `npm audit` highs cleared by
  bumping `axios` → `1.20.0` and `react-router-dom` → `6.30.6`.

Migrations **V19–V21** (analytics indexes; `environments.health_status` +
`environment_health_check`; `audit_log`) — all append-only, every new table
carries `org_id NOT NULL`, `health_status` / `outcome` are `VARCHAR + CHECK` not
PG enum. `spring.task.scheduling.pool.size` `4 → 5`. A pre-existing
`GET /api/v1/runs` 500 (untyped enum bind parameter in
`RunJpaRepository.findAllByOrgId`) is fixed with `CAST(:status AS string)`.
`SchemaMigrationIT` version list is now 1..21.

### Phase 2F — repository-owned framework execution (ADR-009)

**Implementation complete; full-stack verification (WP12: `docker compose up`
against the WP9 network topology + the `repository-run` Playwright smoke) is
still pending.** Connects a GitHub/GitLab repository, resolves a mutable ref
to an immutable commit SHA at enqueue time, and runs its existing
Playwright/JUnit/pytest/Cypress/k6 project inside an isolated, disposable
local Docker runner — through the **unchanged** ADR-006/007 queue, retry,
reaper, webhook, and analytics machinery. **Scope decision (2026-09-04):
suite-authored only** — a repo test case is authored via the case editor's
"Repository" tab (mutually exclusive with `apiRequest`/`browserTest`) and runs
through the existing suite Run-now / CI / schedule flows exactly like any
other case. There is **no** ad-hoc "run now from a connection" endpoint —
`test_runs.suite_id` stays `NOT NULL FK`, and 2F takes no `test_runs`
migration.

- **`scm` module (`apps/api`, hexagonal)** — repository-connection CRUD
  (`POST/GET/PUT/DELETE /api/v1/projects/{projectId}/repository-connections`,
  `GET /api/v1/repository-connections/{id}`) + an outbound "test connection"
  probe (`POST .../{id}/test`, `@RateLimited("scm.test-connection", 30/h)`).
  `ScmPort` (`GitHubScmAdapter`/`GitLabScmAdapter`, JDK `HttpClient`, no new
  dependency) resolves a branch/tag/short-SHA to a full 40-hex commit.
  `RepositoryRunPreflightService` (`ResolveRepositoryRunUseCase`) runs inside
  `RunEnqueueService.enqueue`, **before** `test_runs` is inserted: loads the
  connection (org- + project-scoped), resolves `credentialRef` via
  `EnvScmCredentialResolver`, checks the host allowlist
  (`qualityops.repo-exec.scm.allowed-hosts`) + `OutboundAddressGuard`, calls
  `ScmPort.resolveRef`, and freezes the `RepoTestSnapshot` (resolved SHA +
  digest-pinned `runnerImageRef`) — any failure rolls the whole enqueue back
  (no orphan `test_runs`/`run_queue`/`repository_run` row). A **retry**
  re-runs the frozen SHA unchanged; a **schedule fire** re-resolves the ref.
- **Kafka — additive only, no new topic.** `TestCaseSnapshotItem` gains a 6th
  nullable component `RepoTestSnapshot`; `RunRequestedEvent`/`RunCompletedEvent`
  `4 → 5`; `ResultChunkEvent` `1 → 2` (both gain `repositoryItems` +
  `repositoryProvenance` on `CaseResultSummary`). A repository run is one
  `TestCaseSnapshotItem` per run (a container running one command, not N
  independent cases) — the framework's own report is parsed into N
  `RepositoryTestItem`s carried alongside.
- **Migrations V22–V25** (`apps/api` only; **no worker migration** —
  `worker.execution_attempt.runner_kind='REPOSITORY'` is a new free-text value,
  not a schema change): `V22 repository_connection` (org-+project-scoped,
  partial-unique identity, `credential_ref` opaque-key-only, soft delete),
  `V23` adds `test_cases.repo_test JSONB` (nullable, mutually exclusive with
  `api_request`/`browser_test`), `V24 repository_run` (1:1 with `test_runs`,
  frozen spec columns + execution-telemetry columns filled by the lifecycle/
  result consumers), `V25 repository_test_item` (normalized per-test rows,
  `item_key = sha256(suite+name)` drives an epoch-guarded upsert, kept
  separate from `test_results` so the ADR-008 analytics queries are untouched).
  All new tables `org_id NOT NULL`; every enum-like column `VARCHAR + CHECK`.
- **`RepositoryExecutionRunner` (Worker, `kind() == REPOSITORY`)** — a new
  `ExecutionRunner`, selected with **unconditional precedence** over
  browser/API (gap #8: `repoTest` wins regardless of `WORKER_EXECUTION_MODE`).
  An unregistered `REPOSITORY` runner (old Worker, new API — rolling-deploy
  skew) resolves to a `BlockedRepositoryRunner` sentinel (`BLOCKED
  "repository execution unavailable"`, never an NPE, never a simulated
  fallback). Orchestrates two hardened sibling containers per attempt via a
  new output port `ContainerRunnerPort` (`DockerContainerRunner`, `docker-java`
  + `docker-java-transport-httpclient5`): a *checkout* container
  (platform-controlled `git fetch --depth 1` of the frozen SHA, `EGRESS`
  network) then a *framework* container (the repo's own argv command, exec-form,
  never `sh -c`, on `ISOLATED`/`NetworkMode.NONE` by default or `EGRESS`).
  `HostConfig`: non-root `12000:12000`, `CapDrop ALL`, `no-new-privileges`,
  read-only rootfs, a `noexec,nosuid` tmpfs `/tmp`, a bind-mounted per-attempt
  workspace (created fresh, deleted in a `finally`), memory/CPU/pids/nofile
  limits from `RepoResourceProfile`, no Docker socket, no `--privileged`, no
  host namespaces. `SideEffectClass` flips `NONE_OBSERVED → POSSIBLE` once the
  framework container starts (gap #5) — an in-run retry (ADR-005 §3) only
  covers a transient checkout/pre-exec failure, never after the framework
  command has run. Dedup rides the existing `worker.execution_attempt` claim +
  a deterministic container name (`qualityops-run-<executionId>-<attemptEpoch>-<phase>`)
  with adopt-or-recreate on a name clash; a `RepoContainerSweeper`
  (boot + `@Scheduled`) label-sweeps orphans. Cancellation reuses the
  unchanged `runs.cancel` path — a parallel watcher SIGTERMs then (after a
  grace period) SIGKILLs the container.
- **Runner-image allowlist** — `qualityops.repo-exec.images.<preset>`, one
  digest-pinned ref per `FrameworkPreset` + `checkout`. The API freezes only an
  allowlisted value into `repository_run.runner_image_ref` at enqueue; the
  Worker refuses any `imageRef` not byte-equal to its own copy of the map
  before create (`BLOCKED{reason=image_not_allowlisted}`), and refuses a
  pulled-digest mismatch after (`BLOCKED{reason=digest_mismatch}`). All six
  digests are **real, resolved** refs (`docker pull` → `docker inspect
  --format '{{json .RepoDigests}}'`, the same method `AbstractDockerRunnerIT`
  uses for `alpine/git`), version-controlled and `CODEOWNERS`-guarded in
  `infra/compose/runner-images.env` (the single source of truth for both
  `application.yml` defaults and CI's Trivy scan matrix) — never a placeholder.
- **Report parsing** — `RepoReportFormat ∈ {JUNIT_XML, K6_SUMMARY_JSON}`.
  `JUnitXmlReportParser` covers Playwright/JUnit-Surefire/pytest/Cypress (all
  emit JUnit XML); `K6SummaryReportParser` reads a k6 `--summary-export`
  JSON (run-level pass/fail from the container exit code is exact; the
  check/threshold item breakdown is best-effort). `WorkspacePathResolver`
  resolves every glob match, follows symlinks, and rejects anything outside
  the workspace root (the zip-slip / path-traversal guard). A malformed
  report ⇒ the case is `ERROR` with a safe reason; the run is not aborted.
- **Secrets** — `secretVars`/`credentialRef` are opaque keys resolved by the
  **Worker** at execution time (`EnvFileSecretResolver`); the checkout token
  lives only in the checkout container's tmpfs, never the framework
  container's env. `Redactor.forExecution(Set<String> literals)` adds every
  resolved secret plaintext + the checkout token as an exact-string mask on
  top of the existing regex rules, applied to every streamed console line,
  parsed item message, and provenance field. A secret-bearing run gates raw
  artifact upload behind `upload-secret-run-artifacts` (default `false` →
  `UNAVAILABLE:suppressed-secret-run`); parsed `repository_test_item` rows
  always flow.
- **Compose network split** (`infra/compose/docker-compose.yml`) —
  `qualityops-internal` (`internal: true`; postgres/redis/kafka/minio +
  api/worker/gateway) and `qualityops-runner-egress` (plain bridge; only an
  `EGRESS`-policy repo-run container ever joins it, at container-create time,
  never via compose). A pinned `docker-proxy` (`tecnativa/docker-socket-proxy`)
  fronts the host Docker socket for the Worker with a verb allowlist (no
  `/exec`, `/commit`, `/build`, `/volumes`, Swarm) —
  `qualityops.repo-exec.docker.require-proxy=true` fails Worker startup if
  `DOCKER_HOST` resolves to a raw socket instead (accepted only for local
  `mvn spring-boot:run`, with a loud WARN). An `ISOLATED` framework container
  joins neither network and cannot reach the platform's own data services by
  construction.
- **Frontend (additive)** — `apps/web/src/features/projects/`
  `RepositoryConnectionsTab` + `RepositoryConnectionForm` (a new "Repositories"
  project tab); `apps/web/src/features/suites/RepoTestForm` (a "Repository" tab
  in the case editor — selecting a connection is what marks a case as
  repository-run); `apps/web/src/features/runs/RepositoryExecutionPanel` +
  `RepositoryTestItemsTable` on the run-detail page. `apps/web/src/api/repositories.ts`
  (connection CRUD + test-connection hooks); `GET /api/v1/runs/{id}` and
  `.../results` gained the additive-nullable `repositoryRun` block and
  `meta.repositoryItems`. No new routes — repositories are a tab, matching the
  `EnvironmentsTab` precedent; no "run now from a connection" UI (gap #1).
- **Observability** — `qualityops.repo.{ref_resolve,image_pull,
  container_duration,runs,container_kills,report_parse,items,blocked,
  orphans_swept}` meters (bounded cardinality, no `org` tag).

Two new runtime dependencies, both justified and CI-scanned: `docker-java-core`
+ `docker-java-transport-httpclient5` (Worker only — already transitively
present via Testcontainers) and `tecnativa/docker-socket-proxy` (infra image,
compose/staging only).

### 3. Redis for ephemeral state

**Decision:** Use Redis for session cache, rate limiting, real-time run status,
and WebSocket pub/sub. NOT as a primary data store.

**Why:** Run status changes frequently (every few seconds during execution).
Hitting Postgres for each status update is wasteful. Redis provides sub-ms reads
for the dashboard and natural TTL-based expiry for sessions.

**What goes in Redis:**
- Current run status + progress percentage
- Rate limit counters per API token
- Session/auth cache
- Dashboard widget caches (TTL: 30s)

**What stays in Postgres:**
- Everything else. Redis is ephemeral; if it dies, the app recovers.

### 4. React + TanStack Query for frontend

**Decision:** React 18, TypeScript strict mode, Vite, TanStack Query, Tailwind CSS.

**Why:**
- TanStack Query eliminates the manual `useEffect` + loading state boilerplate
  and gives us caching, polling, and background refresh for free.
- Vite is the fastest dev server for React.
- Tailwind avoids CSS architecture debates and keeps styling co-located.
- TypeScript strict mode catches bugs before they reach the backend.

### 5. Spring Cloud Gateway as the entry point

**Decision:** All frontend requests go through Spring Cloud Gateway, which
routes to the API, worker health endpoints, and static assets.

**Why:**
- Single entry point simplifies CORS, auth, and rate limiting.
- Gateway can add request tracing headers (OpenTelemetry).
- Easy to add canary routing, A/B testing later.
- Learning opportunity for proxy/gateway patterns.

### 6. Flyway for database migrations

**Decision:** All schema changes go through Flyway versioned migrations.
Never modify a migration that has been applied.

**Why:** Reproducible schema across local, staging, and production.
Version-controlled migrations are auditable and rollback-friendly.

### 7. Testcontainers for integration tests

**Decision:** Integration tests use Testcontainers to spin up real Postgres,
Redis, and Kafka instances in Docker containers.

**Why:** Mocking databases leads to false confidence. Real containers catch
SQL syntax issues, constraint violations, and Kafka serialization problems
that mocks would miss.

### 8. Hexagonal architecture for the API

**Decision:** Modules follow hexagonal (ports-and-adapters) architecture.
Business logic depends on interfaces (ports), not on frameworks or infrastructure.

**Why:**
- Testability: domain logic is unit-testable without Spring context.
- Swappability: can replace Postgres with another DB, Kafka with RabbitMQ,
  without touching business logic.
- Clean dependencies: code depends inward (adapters → ports → domain),
  never outward.
- Interview-relevant: one of the most discussed patterns in system design.

**Practical rule:** Start simple (controller → service → repo). Extract ports
and adapters when a module has multiple infrastructure concerns (Kafka +
Redis + JPA) or complex domain logic.

### 9. Event-driven architecture for execution

**Decision:** The execution flow is fully event-driven. The API never calls
the worker directly. All communication goes through Kafka events.

**Why:**
- Loose coupling: API and Worker deploy independently.
- Scalability: add more Worker instances without API changes.
- Resilience: if the Worker is down, events queue in Kafka.
- Auditability: event log is a natural audit trail.
- Replayability: can re-process events for debugging or recovery.

**Event choreography (not orchestration):**

Services react to events autonomously. There is no central orchestrator
telling each service what to do. Each service publishes facts about what
happened, and other services decide how to react.

```
runs.requested → Worker starts (simulated) execution
runs.started   → API: PENDING → RUNNING (conditional UPDATE)
runs.completed → API: {PENDING,RUNNING} → PASSED|FAILED, then generate results
runs.failed    → API: {PENDING,RUNNING} → FAILED (execution errored, not a test
                 failure; no results). Reuses the FAILED status — no ERROR label.
```

### 10. Rate limiting at gateway and application level

**Decision:** Two-tier rate limiting — gateway-level per-client limits and
application-level per-operation limits.

**Why:**
- Gateway-level prevents abuse before requests hit the API (DDoS, scraping).
- Application-level prevents expensive operations (test runs, AI calls) from
  exhausting shared resources, even from legitimate users.
- Redis-backed counters are fast and consistent across API replicas.

### 11. Security-first design

**Decision:** Authentication and authorization are non-negotiable from Phase 1.
TLS in production. OWASP Top 10 compliance as a review checklist.

**Why:**
- Retrofitting security is harder and riskier than building it in.
- As a QA platform, this project handles sensitive data (test results, API keys,
  environment URLs, source code references).
- Practicing security patterns is a core goal of this lab.

**Security progression:**
- Phase 1: JWT + local users + RBAC + HTTPS headers
- Phase 4: OAuth 2.0 + SSO + API tokens + audit logging
- Phase 5: TLS termination at ingress + cert-manager + mTLS (later)

### 12. Multi-tenancy from day one

**Decision:** Every table includes `org_id` or inherits it through a parent
entity. Even in Phase 1 (single-tenant), the column exists and is enforced.

**Why:** Retrofitting multi-tenancy is one of the hardest rewrites. Adding
the column from the start costs almost nothing but saves months later.

### 13. Stripe for payments (no raw card handling)

**Decision:** Use Stripe Checkout (hosted payment page) and Stripe Customer
Portal for all payment flows. Never handle raw card numbers on our servers.

**Why:**
- PCI compliance is extremely expensive and complex to achieve yourself.
- Stripe Checkout is PCI DSS Level 1 compliant out of the box.
- Webhooks give us async subscription lifecycle events — fits the
  event-driven architecture (webhook → API → Kafka event → state update).
- Stripe SDK handles retries, idempotency keys, and error recovery.
- Test mode with Stripe CLI lets you simulate every scenario locally.

**Pattern:** Stripe is the source of truth for payment state. Our database
stores a synchronized copy via webhooks. If they ever disagree, Stripe wins.

## Security architecture

### Authentication flow

```
                                   ┌──────────────┐
                                   │   Identity    │
                                   │   Provider    │
                                   │ (GitHub/Azure │
                                   │   AD/Google)  │
                                   └──────┬───────┘
                                          │ OAuth 2.0 + PKCE
┌─────────┐    HTTPS    ┌─────────┐      │         ┌─────────┐
│ Browser  │───────────►│ Gateway │◄─────┘         │  Redis  │
│ (React)  │◄───────────│ (TLS   │                 │ (session│
│          │  JWT cookie │ termin.)│                 │  cache) │
└─────────┘             └────┬────┘                 └────┬────┘
                             │                           │
                        ┌────▼────┐    JWT validate  ┌───▼────┐
                        │   API   │◄────────────────►│Postgres│
                        │ Server  │   user + roles   │ (users,│
                        │         │                  │  audit) │
                        └─────────┘                  └────────┘
```

### Authentication phases

| Phase | Strategy | Details |
|---|---|---|
| Phase 1 | JWT + local users | Spring Security, bcrypt passwords, hardcoded seed users |
| Phase 4 | OAuth 2.0 / OIDC (SSO) | GitHub, Google, Azure AD via Spring Security OAuth2 Client |
| Phase 4 | MFA / 2FA | Email OTP, SMS OTP (Twilio); optional TOTP authenticator app |
| Phase 4+ | SAML 2.0 | Enterprise SSO for large orgs |

### Authorization model (RBAC)

| Role | Projects | Environments | Suites | Runs | Schedules | Users | Org settings |
|---|---|---|---|---|---|---|---|
| OWNER | CRUD | CRUD | CRUD | trigger + read + cancel; priority HIGH allowed | CRUD; priority HIGH allowed | CRUD | CRUD |
| ADMIN | CRUD | CRUD | CRUD | trigger + read + cancel; priority HIGH allowed | CRUD; priority HIGH allowed | Read + Invite | Read |
| MEMBER | Read | CRUD | CRUD | trigger + read + cancel (priority HIGH ⇒ 403) | CRUD (priority HIGH ⇒ 403) | Read | — |
| VIEWER | Read | Read | Read | Read | Read + next-fires | — | — |

Runs stay immutable (domain rule #2): a `QUEUED` cancel sets `test_runs.status =
CANCELLED` before execution; a `DISPATCHED`/`RUNNING` cancel is cooperative and
the run still terminates through the normal lifecycle.

Every request carries `orgId` from the JWT. Every query filters by `orgId`.
No cross-tenant data access is possible at the query level.

### TLS / HTTPS strategy

| Environment | TLS Termination | Certificate |
|---|---|---|
| Local dev | No TLS (localhost HTTP) | — |
| Staging | Azure Load Balancer / Ingress | Let's Encrypt via cert-manager |
| Production | Azure Load Balancer / Ingress | Let's Encrypt or enterprise CA |

- Minimum TLS 1.2, prefer TLS 1.3.
- HSTS enabled with preload.
- Internal cluster traffic: plain HTTP (later mTLS via service mesh).

### Security headers (set at Gateway)

```
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Content-Security-Policy: default-src 'self'; img-src 'self' data:
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

### Outbound execution requests (SSRF, OWASP A10:2021)

The Worker's real API runner only issues http/https requests. The target host is
resolved and EVERY A/AAAA record is checked against a denylist — loopback,
link-local (incl. `169.254.169.254` metadata), site-local, any-local, multicast,
IPv6 ULA `fc00::/7`, CGNAT `100.64/10`, IPv4-mapped IPv6 and other reserved
ranges. Redirects are disabled. URL userinfo is rejected. A dev allowlist
(`qualityops.worker.execution.ssrf.*`, default off) unlocks named private hosts
for local compose/CI; `169.254.169.254`, any-local and multicast stay blocked
even then. A blocked target ⇒ the case is `BLOCKED`, the run is not aborted.

### Browser execution (SSRF, sub-resources, credentials — Phase 2B2, ADR-004)

The Worker's browser runner executes only a **declarative** scenario — a fixed
set of navigate / click / fill / select / press-key steps and text / URL /
visibility / element-state assertions. There is **no field that carries
JavaScript, a `page.evaluate` body, or a shell command**; an unmappable step
(e.g. an unknown ARIA role) becomes an `ERROR` outcome, never an execution.

- `startUrl` and every `NAVIGATE` step URL pass through the same
  `TargetValidator` as the API runner (resolve → every A/AAAA vs. denylist;
  userinfo rejected; http/https only). Any blocked target ⇒ the whole case is
  `BLOCKED` and Chromium is never launched.
- **Sub-resource interception** (`…browser.block-private-subresources`, default
  on): the fresh `BrowserContext` routes every request, resolves its host, and
  aborts it if any resolved address is on the denylist — so an allowed page that
  embeds `<img src="http://169.254.169.254/…">` cannot reach link-local/private
  space.
- A `FILL` step's value is **never** logged or returned — only its length.
  `finalUrl`, assertion `actual` values and any Playwright error text are run
  through `Redactor`. Element text is recorded only when
  `qualityops.worker.execution.persist-body-snippets` is true (**default false**),
  otherwise `"(text suppressed)"`.
- Screenshots (on failure) and traces are captured to a temp dir, size-capped,
  and swept every 30 min. **Since 2B3 (ADR-005)** they are then staged and
  uploaded best-effort to a private MinIO bucket via `ArtifactStoragePort`
  (org-first key, SSE-S3, retention lifecycle rule); the terminal and each
  `results.chunk` carry an `ArtifactReference` (never bytes/URL). The API mints
  short-TTL presigned GET URLs with a **separate read-only** MinIO credential and
  never proxies bytes. A failed/slow upload can never delay or fail a terminal
  event — it becomes `ArtifactReference` status `UNAVAILABLE`.
- **Browser credentials — delivered in 2B3 (ADR-005):** a `FILL` password/token is
  authored as `BrowserStep.secretValue` (an opaque `secretRef` key), and an API
  header as `HttpHeader.secretRef`. The Worker resolves the plaintext at execution
  time (`EnvFileSecretResolver`; Azure Key Vault is Phase 5) immediately before
  use; it never enters an event, `config_snapshot`, a log, `test_results`, or (by
  default) an artifact. Secret-sourced headers are always masked; secret-bearing
  screenshots are gated (`upload-secret-cases`, default false) with input masking
  and forced trace-off; an unresolvable `secretRef` ⇒ case `BLOCKED`.

### Redaction

Request/response headers on a denylist (Authorization, Cookie, Set-Cookie,
Proxy-Authorization, `*token*`, `*secret*`, `*api-key*`, …) are masked in event
and stored metadata and in every log line. Raw request bodies are never stored or
logged (size only). Response bodies are consumed by a bounded streaming reader
that retains at most `maxResponseBytes` (the full body is never buffered), then
truncated to a small sample and run through secret-pattern regexes (bearer
tokens, JWTs, `AKIA…`, PEM keys, `password=…`) before storage. The
`persist-body-snippets` flag (default false) suppresses the stored `BODY_CONTAINS`
actual value and the response-text portion of an API failure reason as well.

## Rate limiting

### Strategy

Rate limiting happens at two levels:

```
┌─────────────────────────────────────────────┐
│ Gateway (Spring Cloud Gateway + Redis)      │  ← global rate limit
│ Token bucket per API key / IP               │     per-client
│ Headers: X-RateLimit-Limit, Remaining, Reset│
└──────────────────────┬──────────────────────┘
                       │
┌──────────────────────▼──────────────────────┐
│ Application layer (per-operation limits)    │  ← business rate limit
│ e.g., max 100 runs/hour/org                 │     per-operation
│ e.g., max 10 AI analyses/hour/org           │
└─────────────────────────────────────────────┘
```

### Rate limit tiers

| Tier | Requests/min | Run triggers/hour | AI requests/hour |
|---|---|---|---|
| Free | 60 | 50 | 10 |
| Pro | 600 | 500 | 100 |
| API token | 300 | 200 | 50 |

### Implementation

Gateway-level: Spring Cloud Gateway `RequestRateLimiter` filter backed by Redis.
Application-level: Redis counters with TTL per org per operation.

When rate limited, return `429 Too Many Requests` with `Retry-After` header.

## API design

### Design principles
- **RESTful** — resources, not actions. Use HTTP verbs correctly.
- **Versioned** — all endpoints under `/api/v1/`. Breaking changes get `/api/v2/`.
- **Consistent** — same envelope, same error format, same pagination everywhere.
- **Secure by default** — every endpoint authenticated except health + login.
- **Idempotent** — PUT and DELETE are idempotent. POST returns 409 on duplicates.
- **Discoverable** — OpenAPI spec auto-generated from annotations.

### Endpoints

```
# Auth
POST   /auth/login                         # local login → JWT
POST   /auth/refresh                       # refresh access token
POST   /auth/logout                        # revoke refresh token
GET    /auth/oauth2/authorize/{provider}   # OAuth redirect (Phase 4)
GET    /auth/oauth2/callback/{provider}    # OAuth callback (Phase 4)

# Projects
GET    /api/v1/projects                    # list projects (filtered by org)
POST   /api/v1/projects                    # create project
GET    /api/v1/projects/{id}               # get project
PUT    /api/v1/projects/{id}               # update project
DELETE /api/v1/projects/{id}               # soft delete project

# Environments — list/create are nested under project (ownership check needs
# project context); get/update/delete are flat since {id} is already globally
# unique, matching the /api/v1/projects/{id} precedent.
GET    /api/v1/projects/{projectId}/environments  # list environments
POST   /api/v1/projects/{projectId}/environments  # register environment
GET    /api/v1/environments/{id}                  # get environment
PUT    /api/v1/environments/{id}                  # update environment
DELETE /api/v1/environments/{id}                  # soft delete environment
GET    /api/v1/environments/{id}/health           # health status + recent probe history (ADR-008)

# Test suites
GET    /api/v1/projects/{projectId}/suites  # list suites
POST   /api/v1/projects/{projectId}/suites  # create suite
GET    /api/v1/suites/{id}                  # get suite
PUT    /api/v1/suites/{id}                  # update suite
DELETE /api/v1/suites/{id}                  # soft delete suite

# Test cases
GET    /api/v1/suites/{suiteId}/cases  # list cases in suite
POST   /api/v1/suites/{suiteId}/cases  # add case to suite
GET    /api/v1/cases/{id}              # get case
PUT    /api/v1/cases/{id}              # update case
DELETE /api/v1/cases/{id}              # soft delete case

# Test runs — flat, not nested under project, since a run always names its
# project/suite/environment explicitly in the request body; list supports
# optional ?projectId=&suiteId=&status=&queueState= filters. Runs are immutable
# once triggered (domain rule #2); a trigger now ENQUEUES (run_queue) and the
# dispatcher publishes runs.requested ~a tick later (ADR-006).
POST   /api/v1/runs                        # enqueue a test run (optional body priority HIGH|NORMAL|LOW)
GET    /api/v1/runs                        # list runs (optional filters incl. ?queueState=)
GET    /api/v1/runs/{id}                   # get run details (incl. queueState/priority/cancelRequested)
GET    /api/v1/runs/{id}/results           # get run results
POST   /api/v1/runs/{id}/cancel            # cancel a queued (200) or in-flight (202, cooperative) run

# Schedules (Phase 2C, ADR-006) — list/create nested under project; get/update/
# delete/pause/resume/next-fires flat since {id} is globally unique.
GET    /api/v1/projects/{projectId}/schedules  # list schedules
POST   /api/v1/projects/{projectId}/schedules  # create schedule (ONE_TIME | RECURRING)
GET    /api/v1/schedules/{id}                   # get schedule
PUT    /api/v1/schedules/{id}                   # update schedule (recomputes next_fire_at)
DELETE /api/v1/schedules/{id}                   # delete schedule
POST   /api/v1/schedules/{id}/pause             # enabled=false, next_fire_at=null
POST   /api/v1/schedules/{id}/resume            # enabled=true, next_fire_at recomputed
GET    /api/v1/schedules/{id}/next-fires?count= # preview next N fire times (not stored)

# Analytics
GET    /api/v1/projects/{projectId}/analytics  # pass rate + run count, last N days (Phase 1)
GET    /api/v1/analytics/flaky?projectId&window # per-test_case flakiness/stability, last N results (ADR-008)
GET    /api/v1/analytics/trends?projectId&days  # daily run pass/fail + avg/p95 duration (ADR-008)
GET    /api/v1/analytics/slow?projectId&days&limit # slowest test_case_ids by p95 duration_ms (ADR-008)

# Real-time (ADR-008) — STOMP over SockJS
GET    /ws                                     # SockJS handshake (permitAll); JWT on STOMP CONNECT
#      SUBSCRIBE /topic/runs/{runId}           # live run status/progress; org-checked against the run

# API tokens (Phase 4)
POST   /api/v1/tokens                      # create API token
GET    /api/v1/tokens                      # list tokens (masked)
DELETE /api/v1/tokens/{id}                 # revoke token

# Admin
GET    /api/v1/admin/users                 # list org users
POST   /api/v1/admin/users/invite          # invite user
PUT    /api/v1/admin/users/{id}/role       # change user role
GET    /api/v1/admin/audit-log             # view audit log

# Billing / Subscriptions (Phase 4B)
GET    /api/v1/billing/subscription          # current plan + usage
POST   /api/v1/billing/checkout              # create Stripe Checkout session → redirect URL
POST   /api/v1/billing/portal                # create Stripe Customer Portal session → redirect URL
GET    /api/v1/billing/invoices              # invoice history
GET    /api/v1/billing/plans                 # available plans + pricing
POST   /api/v1/billing/webhooks/stripe       # Stripe webhook receiver (public, signature-verified)
```

### Response envelope

**Success:**
```json
{
  "data": { ... },
  "meta": { "page": 1, "pageSize": 20, "total": 142 }
}
```

**Error:**
```json
{
  "error": {
    "code": "PROJECT_NOT_FOUND",
    "message": "Project with id 550e8400-... not found",
    "details": [
      { "field": "name", "message": "must not be blank" }
    ]
  }
}
```

### HTTP status codes used

| Code | Meaning | When |
|---|---|---|
| 200 | OK | Successful GET, PUT |
| 201 | Created | Successful POST |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Validation failure |
| 401 | Unauthorized | Missing or invalid token |
| 403 | Forbidden | Valid token, insufficient permissions |
| 404 | Not Found | Resource doesn't exist (or wrong org) |
| 409 | Conflict | Duplicate resource |
| 429 | Too Many Requests | Rate limited |
| 500 | Internal Server Error | Unexpected failure |
```

## Execution flow (the core loop)

```
1. User clicks "Run Regression" in React UI (or a Schedule fires — same path)
2. Frontend POST /api/v1/runs { projectId, suiteId, environmentId, priority? }
3. Gateway validates auth, forwards to API
4. API (EnqueueRunUseCase, one tx): validate target → freeze snapshot → mint
   execution_id → INSERT test_runs (PENDING) + run_queue (QUEUED) with the fully
   serialised RunRequestedEvent frozen in requested_event_json. NOTHING published.
4a. QueueDispatchJob (@Scheduled + ShedLock "queue-dispatch"): count active per org,
    select QUEUED candidates by aged effective priority (FOR UPDATE SKIP LOCKED),
    respect per-org concurrency, then per row CLAIM (queue_state QUEUED→DISPATCHED,
    committed) and only then publish RunRequestedEvent to runs.requested
    synchronously (a lost send rolls the row back to QUEUED / to FAILED at the
    attempt ceiling). A run cancelled while QUEUED is set CANCELLED here with no
    Kafka and is never picked.
6. Worker consumes runs.requested (group worker-execution)
6a. Worker durably CLAIMs the attempt (INSERT … ON CONFLICT on worker.execution_attempt);
    an existing COMPLETED claim ⇒ re-emit the cached terminal event and stop
7. Worker publishes runs.started → API RunLifecycleConsumer: PENDING → RUNNING
8. Worker resolves a runner per case (browserTest present ⇒ a declarative Playwright
   scenario in a fresh BrowserContext + SSRF guard + sub-resource block + redaction;
   else apiRequest present ⇒ real HTTP via JDK HttpClient + SSRF guard + redaction
   + bounded response memory; else simulated)
9. Worker aggregates a run outcome from the per-case verdicts
10. Worker writes the terminal to the ledger, then publishes runs.completed(outcome,
    snapshot, per-case summary) — or runs.failed on an interrupt / harness fault only
11. API RunLifecycleConsumer (group api-execution): {PENDING,RUNNING} → terminal status,
    matched on org_id AND execution_id (stale/foreign execution_id ⇒ 0-row no-op);
    on a moved row it also advances run_queue (DISPATCHED→RUNNING→COMPLETED|FAILED)
12. API RunCompletedConsumer (group api-results): one test_results row per case from the
    event's caseResults (real verdict/duration/redacted reason; legacy fabrication for v1)
13. Dashboard reflects final status + results via polling (WebSocket is Phase 2E)

A cancel of a DISPATCHED/RUNNING run publishes RunCancelRequestedEvent to
runs.cancel; the Worker's RunCancelConsumer records it in an in-memory
CancellationRegistry, and the next between-cases check errors the remaining cases
("run cancelled") — the run still completes (aggregate FAILED).
```

## Technology decisions log

| Decision | Chosen | Alternatives considered | Why |
|---|---|---|---|
| Backend language | Java 21 | Kotlin, Go, Python | Industry standard for enterprise, virtual threads, strong ecosystem |
| Backend framework | Spring Boot 3 | Quarkus, Micronaut | Most mature, best documentation, widest community |
| Frontend | React 18 + TS | Next.js, Angular, Vue | Most hiring-relevant, flexible, great tooling |
| Build tool | Maven | Gradle | More predictable, XML is annoying but unambiguous |
| DB | PostgreSQL | MySQL, MongoDB | Best for relational data, JSONB for flexible fields |
| Worker dedup store | Postgres claim table (own `worker` schema) | Redis SETNX, Kafka EOS, API callback | Durable across restart; not authoritative; no sync API coupling (ADR-003) |
| Worker HTTP client | JDK `java.net.http.HttpClient` | WebClient, Apache HttpClient, RestClient | No dependency, virtual-thread friendly, transparent redirect/SSRF control |
| Browser runner | Playwright for Java embedded in the Worker | Separate Node runner service | Reuse the `ExecutionRunner` port + SSRF/redaction/ledger; zero new deployable; adapter swappable behind `PlaywrightBrowser` later (ADR-004) |
| Artifact store client | MinIO Java client (`io.minio:minio`) behind `ArtifactStoragePort` | AWS SDK v2 S3 + `S3Presigner`, Azure Blob SDK now | One lib for Worker `put` + API presign; smallest full-S3 tree; first-class custom-endpoint/path-style; neither SDK survives the Azure-Blob cloud move — only the port does (ADR-005) |
| Local artifact store (test/dev) | MinIO (`MinIOContainer`, compose service) | LocalStack-S3 | ~5× smaller image, ~10× faster start, full presign/SSE-S3/lifecycle fidelity, prod-parity (ADR-005) |
| Per-case attempt counter | in-memory loop variable + API epoch-guarded upsert | durable `worker.case_attempt` table | Retry loop lives inside one `processRunRequested`; a redelivered/stolen execution restarts all cases anyway (ADR-005 §3.2) |
| Cache | Redis | Memcached, Hazelcast | Versatile (cache + pub/sub + rate limit), industry standard |
| Messaging | Kafka | RabbitMQ, Redis Streams | Best for event-driven architecture learning, exactly-once semantics |
| Gateway | Spring Cloud Gateway | Traefik, Kong, NGINX | Stays in Java ecosystem, easy to customize |
| Scheduler leader election | ShedLock + PostgreSQL (`shedlock` table) | Quartz cluster, Redis SETNX lock, K8s Lease, `pg_advisory_lock` | Durable (Redis is ephemeral), no second scheduler/jobstore, `usingDbTime()` + TTL auto-expiry + documented ops story for free; K8s Lease earmarked for Phase 5 (ADR-006) |
| Cron flavour | Spring `CronExpression` (6-field, `ZonedDateTime`) | Quartz cron, `cron-utils`, hand-rolled `java.time` | Already on the classpath via `spring-context`; correct DST via `java.time` zone rules; no extra dependency (ADR-006) |
| Queue store | dedicated `run_queue` table (`VARCHAR + CHECK`) | columns on `test_runs`, PG `ENUM` types, priority Kafka topics | Keeps the immutable run aggregate clean; `ALTER … CHECK` is transaction-safe as states churn across 2C/2D; a DB-ordered dispatcher expresses priority aging + tenant fairness that priority topics cannot (ADR-006) |
| On-wire job freezing | `run_queue.requested_event_json` mini-outbox | re-derive the event in the dispatcher, full transactional outbox | Byte-stable job frozen with the run; re-map-free dispatcher; one hop, one event type — a full outbox stays a Phase-7 exercise (ADR-006) |
| Migrations | Flyway | Liquibase | Simpler, SQL-native, widely adopted |
| Containers | Docker + Compose | Podman | Docker Desktop is ubiquitous, Compose is simple |
| CI/CD | GitHub Actions | Jenkins, GitLab CI | Free for public repos, native GitHub integration |
| E2E testing | Playwright | Cypress, Selenium | Fastest, best DX, MCP integration |
| IaC | Terraform | Bicep, Pulumi, CloudFormation | Multi-cloud transferable, industry standard, modular |
| Orchestration | AKS (Helm) | Azure App Service, ECS | Full K8s learning, portable skills |

## Extending this project

For lab/playground work — system design concepts map, **k6 load testing**,
and **Google Stitch + DESIGN.md** for the frontend — see Phase 7 in
`docs/product/ROADMAP.md`.

```
Adding a new domain module?
  → Create package under com.qualityops.api.<module>
  → Follow the standard structure: controller, service, repository, dto, model
  → Add Flyway migration for any new tables
  → Add module entry in ARCHITECTURE.md
  → Create tests with Testcontainers

Adding a new Kafka event?
  → Define the event record in the producing module's event/ package
  → Register the topic in config
  → Add consumer in the consuming module
  → Document the event in this file's execution flow section

Adding a new API endpoint?
  → Follow REST conventions above
  → Add OpenAPI annotation
  → Add integration test
  → Update Postman collection if it exists

Adding frontend pages?
  → Create feature module in src/features/<name>/
  → Add route in router config
  → Use TanStack Query for data fetching
  → Add Vitest tests for logic, Playwright test for critical paths

Changing infrastructure?
  → Update docker-compose.yml for local dev
  → Update Helm charts for Kubernetes deployments
  → Update Terraform modules for Azure resources
  → Create ADR in docs/architecture/decisions/
  → Update this file
```

## Dependencies

| Package | Layer | Why |
|---|---|---|
| Spring Boot 3 | Backend | Application framework |
| Spring Data JPA | Backend | Database access |
| Spring Security | Backend | Authentication + authorization |
| Spring Cloud Gateway | Gateway | Routing + filtering |
| Spring Kafka | Backend + Worker | Kafka producer/consumer |
| Spring Data Redis | Backend | Redis client |
| Flyway | Backend | Database migrations |
| net.javacrumbs.shedlock (shedlock-spring + shedlock-provider-jdbc-template) | Backend | Leader election for the `@Scheduled` tick + dispatcher over the `shedlock` PostgreSQL table (ADR-006) |
| io.micrometer:micrometer-registry-prometheus | Backend | Exports the queue/schedule Micrometer meters on `/actuator/prometheus` (ADR-006) |
| Spring `CronExpression` (spring-context, already present) | Backend | 6-field cron + IANA time zone, DST-correct, wrapped by `CronCalculator` (ADR-006) |
| Testcontainers | Backend (test) | Real containers in integration tests |
| JUnit 5 | Backend (test) | Test framework |
| React 18 | Frontend | UI library |
| TanStack Query | Frontend | Server state management |
| Vite | Frontend (build) | Dev server + bundler |
| Tailwind CSS | Frontend | Utility-first CSS |
| Vitest | Frontend (test) | Unit + component testing |
| Playwright | E2E (test) | Browser automation |
| PostgreSQL 16 | Infra | Primary database |
| Redis 7 | Infra | Cache + pub/sub |
| Apache Kafka | Infra | Event streaming |
| qualityops-shared-events | Shared (api + worker) | Kafka event contract records (com.qualityops.events) |
| Spring Web (MVC) | Worker | Serves /actuator/health only |
| Spring Boot Actuator | Worker | Health endpoint for the Docker/compose healthcheck |
| spring-boot-starter-jdbc | Worker | JdbcTemplate for the `worker.execution_attempt` ledger (ADR-003) |
| PostgreSQL driver | Worker | `worker` schema only |
| Flyway (core + database-postgresql) | Worker | `worker.flyway_schema_history` migration stream |
| JDK `java.net.http.HttpClient` | Worker | Real API-test execution (no added dependency) |
| com.microsoft.playwright:playwright | Worker | Real declarative browser-test execution (ADR-004) |
| mcr.microsoft.com/playwright/java base image | Worker image | Bundled Chromium + OS libraries (glibc/jammy, ~2 GB) for the browser runner |
| io.minio:minio | Worker + API | S3-compatible artifact storage — Worker `put` (write-only key), API presign GET + head (read-only key). ADR-005; alternatives: AWS SDK v2 S3, Azure Blob SDK (Phase 5). |
| org.testcontainers:minio (MinIOContainer) | Backend (test) | Real MinIO for `S3ArtifactStorageIT` / `ArtifactControllerIT` (chosen over LocalStack-S3: smaller, faster, full presign/SSE fidelity) |
| minio/minio + minio/mc images | Infra | Local/dev test-artifact object store + one-shot bucket/policy bootstrap in compose |
