# ADR-006: Queue-driven scheduling and execution control

## Status

Accepted.

- **Extends** ADR-002 §1 (the API remains the sole writer of authoritative run/result state), ADR-002 §2 (the run lifecycle topics and their `executionId`-guarded conditional `UPDATE`s), and ADR-003 §4 (this ADR supplies the `CancellationToken` signal source the Worker was left wiring as `CancellationToken.never()`).
- **Preserves** ADR-003 §3 as clarified by ADR-005 §preamble — the Worker's **Postgres** reach stays exactly `worker.execution_attempt` in its own `worker` schema. **There is no Worker migration in this increment.** The only Worker addition is an in-memory `CancellationRegistry` fed by a new Kafka topic.
- **Preserves** the ADR-005 `results.chunk` contract, the epoch-monotone `test_results` / `test_result_artifacts` upsert, and the bounded in-run retry in `RunExecutionService.runCases`. None of them change.
- **Does not extend the `sealed interface RunEvent`.** The cancel signal is a standalone command record (`RunCancelRequestedEvent`), outside the seal, for the same reasons ADR-005 kept `ResultChunkEvent` outside it.
- Realises `docs/product/PHASE-2-PLAN.md` §2C ("Scheduling, leader coordination, queue state, priorities, tenant fairness"). It **narrows** that plan text in three places: the migrations are `V12`–`V15` (not `V9`/`V10`, which are already taken, and not a single file); priority is resolved by a **database-ordered dispatcher**, not by `runs.requested.high|normal|low` priority topics; and queued-run cancel controls (`GET …?queueState=`, `POST …/{id}/cancel`) land **here** rather than being held to 2D, because they are the only place the ADR-003 §4 cancel wiring can be closed.

## Context

After Phase 2B3 the execution path is: `RunService.trigger` mints an `executionId`, persists `test_runs` PENDING, and **immediately** fire-and-forget publishes `RunRequestedEvent` to `runs.requested`. The Worker consumes it (group `worker-execution`), durably claims `worker.execution_attempt`, runs each snapshot case through a per-case `ExecutionRunner` (browser / API / simulated) with bounded in-run retry and best-effort artifact upload, then publishes `runs.started` and one of `runs.completed` / `runs.failed`. The API's `RunLifecycleService` applies org- + `executionId`-guarded conditional status `UPDATE`s; `ResultService` upserts `test_results` / `test_result_artifacts` from `results.chunk` and the terminal.

Four capabilities are missing, and all four were named for 2C:

1. **Scheduling.** There is no way to say "run this suite every weeknight at 02:00 Europe/Warsaw" or "run it once at 14:30 on Friday". Every run is a synchronous human `POST`.
2. **Leader coordination.** `ARCHITECTURE.md` already draws two API replicas. A naive `@Scheduled` scheduler on each replica would fire every schedule N times.
3. **Authoritative queue state with priorities and tenant fairness.** `runs.requested` is published the instant a run is triggered, so there is no admission control: no priority, no per-tenant concurrency cap, no "hold this run until capacity frees", and no cheap way to see how many runs are waiting.
4. **Cancellation.** ADR-003 §4 designed cooperative cancellation (`CancellationToken` polled between cases, passed into the runner) but left the signal source to "Phase 2D"; the call site hardcodes `CancellationToken.never()`. There is no cancel endpoint and no channel to reach an in-flight execution.

Constraints carried from ADR-001…005: multi-tenancy on every event, row, and object key; idempotency under Kafka at-least-once; runs are immutable once triggered (config snapshotted at trigger time — domain rule #2); the API is the sole writer of authoritative state and **never** calls the Worker synchronously (domain rule #9 / ARCHITECTURE decision #9); event-driven, not request-driven, for execution; boring, reversible technology; Flyway for all schema changes, append-only; modules communicate through services/ports, not shared internals; every table carries `org_id` (or inherits it).

Two invariants shape the design:

- **Admission control lives entirely in the API, before a single `runs.requested` is published.** The Worker never learns a run's priority or its org's concurrency limit. It keeps consuming one topic, one group, exactly as today.
- **A run that is cancelled while still queued must never execute.** This is a hard guarantee. A run that is already dispatched or executing can only be cancelled *cooperatively* and may still complete.

## Decision

### 1. A `scheduling` module in `apps/api` (`com.qualityops.api.scheduling`)

#### 1.1 The `schedule` aggregate

A new module `com.qualityops.api.scheduling`, hexagonal layout (controller → use-case ports → service → repository adapter), owning one aggregate, `Schedule`:

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `org_id` | UUID | tenant; every query filters on it |
| `project_id`, `suite_id`, `environment_id` | UUID | the run target; validated at create/update to belong to `org_id` and to be mutually consistent (`suite.projectId == project_id`, `environment.projectId == project_id`) — reusing the exact checks in `RunService.trigger` |
| `name` | varchar(200) | human label |
| `kind` | `ONE_TIME` \| `RECURRING` | |
| `cron_expression` | varchar(120), nullable | non-null iff `RECURRING` |
| `time_zone` | varchar(64), nullable | IANA zone id (`ZoneId.of`), non-null iff `RECURRING` |
| `fire_at` | timestamptz, nullable | absolute instant, non-null iff `ONE_TIME` |
| `priority` | `HIGH` \| `NORMAL` \| `LOW` | default `NORMAL`; `HIGH` gated by RBAC (§4.1) |
| `catch_up_policy` | `SKIP_MISSED` \| `FIRE_ONCE` | default `SKIP_MISSED` |
| `enabled` | boolean | pause/resume flag; default true |
| `next_fire_at` | timestamptz, nullable | **materialised** absolute next occurrence; null when disabled, or when a one-time schedule has fired |
| `last_fired_at` | timestamptz, nullable | |
| `last_error`, `last_error_at` | text / timestamptz, nullable | set when a fire is abandoned because the target no longer validates (§1.4) |
| `created_by` | UUID | FK → users; the identity a fired run is attributed to (`triggered_by`) |
| `created_at`, `updated_at` | timestamptz | |

#### 1.2 Cron flavour: Spring `CronExpression`, `ZonedDateTime`-based

`org.springframework.scheduling.support.CronExpression` (6-field: second…day-of-week; supports ranges, `/`, `L`, `#`, `@daily`-style macros) is already on the classpath via `spring-context`. `CronExpression.parse(expr)` validates; `.next(ZonedDateTime.now(ZoneId.of(tz)))` computes the next occurrence with correct DST handling (it delegates to `java.time` zone rules); iterating `.next(...)` yields the N-fire preview and the most-recent-past occurrence used for `FIRE_ONCE` catch-up. All of this is wrapped in one worker-domain helper, `CronCalculator`, so no other class touches `CronExpression`.

- **Quartz / `spring-boot-starter-quartz`** — rejected: brings a second scheduler, its own thread pool, its own 11-table jobstore, DB-row-lock clustering, misfire policies, and a 7-field cron dialect. We already have `@Scheduled` for cadence and ShedLock (§2) for leader election; Quartz duplicates both.
- **`com.cronutils:cron-utils`** — rejected: a good library, but its only unique value here is human-readable descriptions (nice-to-have) and Quartz-syntax parsing (not needed — no `?` day-of-week). Documented as the drop-in if 2D's CI-trigger API must accept Quartz-syntax crons from Jenkins/GitLab.
- **Hand-rolled `java.time`** — rejected: `L`/`#`/range handling is error-prone.

`next_fire_at` is **stored as an absolute `TIMESTAMPTZ`** and recomputed on create, on update, on resume, and after every fire. The hot path — the tick query — is therefore a pure indexed range scan with **no cron parsing**:

```sql
SELECT * FROM schedule
WHERE enabled AND next_fire_at IS NOT NULL AND next_fire_at <= now()
ORDER BY next_fire_at
LIMIT :tick-batch-size
FOR UPDATE SKIP LOCKED
```

backed by `CREATE INDEX idx_schedule_due ON schedule (next_fire_at) WHERE enabled AND next_fire_at IS NOT NULL`.

#### 1.3 The tick

`ScheduleTickJob` — a `@Scheduled(fixedDelayString = "${qualityops.scheduling.tick-interval:PT15S}")` bean, annotated `@SchedulerLock(name = "scheduling-tick", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")` (§2). Under the lock it runs the query above and, for each due schedule, invokes `ScheduleFireService.fire(schedule)` in its **own** `@Transactional` unit of work (one failing schedule must not roll back the others). `fire` does exactly one thing per logical occurrence and then advances `next_fire_at`.

#### 1.4 The fire path — the same use case as `POST /api/v1/runs`

`ScheduleFireService.fire` never re-implements `RunService.trigger`. Both call a new shared input port:

```
com.qualityops.api.execution.application.port.in.EnqueueRunUseCase
    EnqueueRunResult enqueue(EnqueueRunCommand cmd)   // cmd: orgId, projectId, suiteId,
        environmentId, triggeredBy, priority, source (MANUAL|SCHEDULE), scheduleId (nullable)
```

implemented by `RunEnqueueService` (extracted from today's `RunService.trigger` body). In **one transaction** it:

1. resolves + validates project / suite / environment (existing checks),
2. freezes the test-case snapshot into `RunConfigSnapshot` exactly as today,
3. mints `executionId` and `correlationId`,
4. inserts `test_runs` PENDING (unchanged shape),
5. inserts a `run_queue` row `QUEUED` (§3), storing the **fully serialised** `RunRequestedEvent` in `run_queue.requested_event_json` (a deliberate mini-outbox — see §3.2),
6. **publishes nothing.**

`fire` wraps that in its occurrence guard:

- Validate the target still exists and belongs to the org. If not → **abandon**: in a separate tx set `enabled = false`, `last_error`, `last_error_at`; do **not** retry every tick.
- `INSERT INTO schedule_fire (org_id, schedule_id, fire_slot) VALUES (…) ON CONFLICT (schedule_id, fire_slot) DO NOTHING`. `fire_slot` (§1.5) is the truncated scheduled instant.
- If the insert affected **0 rows**, this occurrence was already fired (clock skew, a retried tick, two replicas racing the same `next_fire_at`) → skip `enqueue`, still advance `next_fire_at`.
- If it affected **1 row**, call `EnqueueRunUseCase.enqueue(source = SCHEDULE, scheduleId = …, triggeredBy = schedule.createdBy, priority = schedule.priority)`, then set `schedule_fire.run_id`, `schedule.last_fired_at = now()`.
- Advance `next_fire_at`: `RECURRING` → `CronCalculator.next(cron, tz, now())`; `ONE_TIME` → `NULL` and `enabled = false`.

The whole guard-plus-enqueue is one transaction: an `enqueue` failure rolls the `schedule_fire` row back and the slot re-fires next tick (bounded — a target that no longer validates is caught by the abandon check above, not by infinite retry).

#### 1.5 `fire_slot` and catch-up, defined precisely

`fire_slot` (TIMESTAMPTZ, part of `UNIQUE (schedule_id, fire_slot)`):

- **RECURRING, on-time**: `fire_slot = schedule.next_fire_at` — the exact instant that made the row due.
- **ONE_TIME**: `fire_slot = schedule.fire_at`.
- **RECURRING, catch-up** (`next_fire_at` is far in the past because the API was down): `fire_slot = CronCalculator.previousOccurrence(cron, tz, now())` — the most recent scheduled instant `≤ now()`. Attributing the make-up run to a real slot keeps the dedup key meaningful.

`catch_up_policy` when a tick finds `next_fire_at` more than one interval in the past:

| Policy | Make-up runs enqueued | `next_fire_at` after |
|---|---|---|
| `SKIP_MISSED` (default) | **zero** — the missed windows are skipped, no `schedule_fire` row, no `enqueue` | `CronCalculator.next(cron, tz, now())` |
| `FIRE_ONCE` | **exactly one**, at `fire_slot = previousOccurrence(...)` | `CronCalculator.next(cron, tz, now())` |

"Skip" means skip: `SKIP_MISSED` never enqueues a catch-up run. `FIRE_ONCE` collapses any number of missed windows into a single make-up run. Neither ever enqueues more than one.

#### 1.6 Endpoints

Nested list/create (ownership needs project context, matching the `/projects/{projectId}/environments` precedent); flat get/update/delete (`{id}` is globally unique, matching `/environments/{id}`):

```
GET    /api/v1/projects/{projectId}/schedules            # list (org- + project-scoped, paged)
POST   /api/v1/projects/{projectId}/schedules            # create
GET    /api/v1/schedules/{id}                            # get
PUT    /api/v1/schedules/{id}                            # update (recomputes next_fire_at)
DELETE /api/v1/schedules/{id}                            # delete (schedule_fire rows cascade)
POST   /api/v1/schedules/{id}/pause                      # enabled=false, next_fire_at=NULL
POST   /api/v1/schedules/{id}/resume                     # enabled=true, next_fire_at=recompute
GET    /api/v1/schedules/{id}/next-fires?count=          # live preview (count clamped 1..50); not stored
```

- `@PreAuthorize`: create/update/delete/pause/resume → `hasAnyRole('OWNER','ADMIN','MEMBER')`; list/get/next-fires → also `VIEWER`. Setting `priority = HIGH` additionally requires `hasAnyRole('OWNER','ADMIN')` via a body-aware SpEL guard: `@PreAuthorize("#request.priority == null or #request.priority != 'HIGH' or hasAnyRole('OWNER','ADMIN')")`.
- Validation (`@Valid` records + a `ConstraintValidator`): `cron_expression` parses via `CronExpression.parse`; `time_zone` via `ZoneId.of`; `@AssertTrue` cross-field — `RECURRING` ⇒ cron + tz set and `fire_at` null; `ONE_TIME` ⇒ `fire_at` set (and in the future) and cron/tz null. A target `project`/`suite`/`environment` not in the caller's org ⇒ `404` (never confirm another tenant's resource).
- No new Spring Boot app. The tick and the dispatcher (§3) are `@Scheduled` beans inside `apps/api`.

### 2. Leader coordination — ShedLock backed by PostgreSQL

#### 2.1 Library and table

Add `net.javacrumbs.shedlock:shedlock-spring` and `net.javacrumbs.shedlock:shedlock-provider-jdbc-template` (version pinned via the ShedLock BOM in the parent `dependencyManagement`). A `@Configuration` class carries `@EnableScheduling` (the API has no `@Scheduled` today) and `@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")`, and declares:

```java
@Bean LockProvider lockProvider(DataSource ds) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(ds))
            .usingDbTime()          // DB clock, not host clock — critical for multi-replica
            .build());
}
```

`V12` creates the canonical ShedLock table (the DDL is in the appendix). It carries **no `org_id`** — like `flyway_schema_history`, it is infrastructure coordination, not tenant data; this is a deliberate, documented exception to the "every table has `org_id`" rule.

#### 2.2 Granularity: one global lock per job, not per schedule

Two `@Scheduled` beans, two global lock names:

- `ScheduleTickJob` → `@SchedulerLock(name = "scheduling-tick", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")`, cadence `tick-interval` (default 15 s).
- `QueueDispatchJob` → `@SchedulerLock(name = "queue-dispatch", lockAtMostFor = "PT1M", lockAtLeastFor = "PT2S")`, cadence `dispatch-interval` (default 2 s).

They are separate so a slow schedule scan cannot stall dispatch and vice-versa. `spring.task.scheduling.pool.size = 2` so the two run on distinct threads.

**Per-schedule locks are rejected.** Double-*fire* is already made impossible by the `schedule_fire (schedule_id, fire_slot)` unique ledger (§1.4) and double-*dispatch* by the conditional claim `UPDATE` (§3.3). Leader election therefore only needs to stop two replicas running the same *scan* at the same moment — redundant work and needless contention on `schedule_fire` / `run_queue`. A single cheap global lock per job does that; per-schedule locks would add one lock row and one acquisition per schedule per tick, plus fairness machinery, for zero correctness gain.

#### 2.3 Behaviour when the lock store (PostgreSQL) is unavailable

`JdbcTemplateLockProvider` throws; the `@Scheduled` invocation is skipped and retried on the next `fixedDelay`. **No run is fired or dispatched, and nothing is lost**: due schedules keep their `next_fire_at`, `QUEUED` rows stay `QUEUED`. A prolonged PostgreSQL outage means no scheduled or queued runs progress until PostgreSQL recovers — acceptable, because the API is the sole writer of authoritative state and the entire platform already hard-depends on PostgreSQL.

`lockAtMostFor` is sized well above each job's real worst-case runtime (sub-second normally; minutes only under a pathological batch) so a crashed holder's lock auto-expires but a slow-but-alive holder is never double-run. `lockAtLeastFor` keeps the lock briefly held even on a fast run, damping host-clock skew between replicas so a second replica cannot grab the lock inside the same logical window.

### 3. The test queue — PostgreSQL authoritative, Kafka transports the immutable job

#### 3.1 A dedicated `run_queue` table, not columns on `test_runs`

`run_queue` is a new table, 1:1 with `test_runs` via a `UNIQUE` `run_id` FK.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `org_id` | UUID | tenant |
| `run_id` | UUID | `UNIQUE` FK → test_runs |
| `schedule_id` | UUID, nullable | provenance; FK → schedule |
| `priority` | varchar(16) `CHECK IN ('HIGH','NORMAL','LOW')` | default `NORMAL` |
| `queue_state` | varchar(16) `CHECK IN ('QUEUED','DISPATCHED','RUNNING','COMPLETED','FAILED','CANCELLED')` | default `QUEUED` |
| `requested_event_json` | jsonb | the frozen `RunRequestedEvent` (§3.2); nulled on any terminal transition |
| `enqueued_at` | timestamptz | |
| `dispatched_at` | timestamptz, nullable | |
| `dispatch_attempts` | int | default 0 |
| `last_dispatch_at` | timestamptz, nullable | |
| `cancel_requested` | boolean | default false |
| `cancel_requested_at` | timestamptz, nullable | |
| `terminal_at` | timestamptz, nullable | |
| `created_at` | timestamptz | |

Indexes: `idx_run_queue_dispatch ON run_queue (priority, enqueued_at) WHERE queue_state = 'QUEUED'` (the dispatch scan); `idx_run_queue_active ON run_queue (org_id) WHERE queue_state IN ('DISPATCHED','RUNNING')` (the concurrency count); plus `(queue_state)`, `(org_id)`, `(schedule_id)`.

`priority` and `queue_state` are `VARCHAR + CHECK`, **not** PostgreSQL `ENUM` types (unlike `run_status` in V6). Queue states will churn across 2C/2D (e.g. a `HELD` state, a `DISPATCH_FAILED` state); `ALTER TABLE … ADD/DROP CHECK` is cheaper and safer than `ALTER TYPE … ADD VALUE` (which cannot run in a transaction and cannot remove a value). The adapter maps to Java enums.

**Columns on `test_runs` are rejected**: `test_runs` is the immutable run record (domain rule #2). The queue has a mutable lifecycle (`queue_state` churn, `cancel_requested`, `dispatch_attempts`) that does not belong on the immutable aggregate, and every read on the hot run-history path would carry dead queue columns. A separate table keeps the immutable/mutable split clean and lets the queue be pruned or rebuilt without touching run history.

#### 3.2 Why the frozen `requested_event_json` (a mini-outbox)

At `enqueue` time, in the same transaction as the `test_runs` + `run_queue` insert, the full `RunRequestedEvent` is serialised and stored in `run_queue.requested_event_json`. The dispatcher reads that column, deserialises it back to a `RunRequestedEvent` with the shared `ObjectMapper`, and publishes it **verbatim** (key = `runId`) through the existing `JsonSerializer` + type-header path — no re-derivation from the config snapshot, no re-mapping.

Rationale: (a) it matches the "immutable run snapshot on the wire" principle already stated in `ARCHITECTURE.md` — the event is frozen with the run exactly as `config_snapshot` is; (b) it mirrors `worker.execution_attempt.terminal_event_json` (ADR-003 §3.4); (c) it makes the dispatcher trivial and re-map-free; (d) a later change to `RunService.toWire*` cannot retroactively alter an already-queued event (correct — it was frozen under the contract in force at enqueue). Cost: a few KB of transient JSONB per queued row, nulled on terminal. It is **not** a general transactional outbox (only this one event type, only for the QUEUED→DISPATCHED hop); a full outbox stays a Phase-7 exercise.

#### 3.3 State machine and composition with the existing lifecycle

```
enqueue         test_runs: (insert) PENDING          run_queue: (insert) QUEUED     [no Kafka]
dispatch        test_runs: PENDING (unchanged)       run_queue: QUEUED -> DISPATCHED, then publish runs.requested
runs.started    test_runs: PENDING -> RUNNING        run_queue: DISPATCHED -> RUNNING
runs.completed  test_runs: {PENDING,RUNNING} -> PASSED|FAILED   run_queue: {DISPATCHED,RUNNING} -> COMPLETED
runs.failed     test_runs: {PENDING,RUNNING} -> FAILED          run_queue: {DISPATCHED,RUNNING} -> FAILED
cancel(QUEUED)  test_runs: PENDING -> CANCELLED       run_queue: QUEUED -> CANCELLED  [no Kafka]
cancel(D/R)     test_runs: unchanged                  run_queue: cancel_requested=true, publish runs.cancel
```

- **Worker path is unchanged.** It still consumes `runs.requested` (group `worker-execution`), claims `worker.execution_attempt`, publishes `runs.started` / `runs.completed` / `runs.failed`. It has no `run_queue` access and never will (ADR-003 §3).
- **`RunLifecycleService` gains the `run_queue` transitions.** Each handler is one `@Transactional` method that (1) performs the existing org- + `executionId`-guarded conditional `UPDATE` on `test_runs` and, **only if that returned 1 row**, (2) performs a conditional `UPDATE` on `run_queue` guarded by `run_id + org_id + queue_state IN (<expected sources>)`. Gating (2) on (1) means the `run_queue` write inherits the `executionId` and org guarantees without a redundant `execution_id` column on `run_queue` (which is 1:1 with `run_id`, itself 1:1 with `executionId` in 2C). A redelivered `runs.started` / `runs.completed` / `runs.failed` matches 0 rows in step (1) → step (2) is skipped → logged no-op, exactly like today.
- `runs.completed` maps to `run_queue.COMPLETED` regardless of the PASSED/FAILED **test** outcome (the run executed to completion); `runs.failed` maps to `run_queue.FAILED` (execution/harness fault). This mirrors the existing `test_runs` semantics.
- `terminal_at` is set and `requested_event_json` is nulled on every terminal transition.

#### 3.4 The dispatcher, and dispatch idempotency

`QueueDispatchJob` (`@Scheduled`, `@SchedulerLock("queue-dispatch")`) calls `QueueDispatchService.dispatchAvailable()`:

1. `SELECT org_id, COUNT(*) FROM run_queue WHERE queue_state IN ('DISPATCHED','RUNNING') GROUP BY org_id` → active-count map.
2. Load `org_run_concurrency` overrides (§4.2); default from config for orgs absent.
3. Select candidates: `WHERE queue_state = 'QUEUED' ORDER BY <effective-priority expr> DESC, enqueued_at ASC LIMIT :dispatch-batch-size FOR UPDATE SKIP LOCKED` (the effective-priority expression is §4.1).
4. Walk candidates; per org track `dispatchedThisTick`; dispatch a row only while `active[org] + dispatchedThisTick[org] < limit[org]`, else leave it `QUEUED` and move to the next candidate (which may belong to another org — this is the fairness mechanism, §4.2).
5. For each row to dispatch — **claim, then publish**:
   - `UPDATE run_queue SET queue_state='DISPATCHED', dispatched_at=now(), dispatch_attempts=dispatch_attempts+1, last_dispatch_at=now() WHERE run_id=:id AND queue_state='QUEUED'` — if it affects **0 rows** (a concurrent cancel won, §5), skip.
   - Then `kafkaTemplate.send("runs.requested", runId, event).get(:send-timeout)` — **synchronous, ack-awaited**.
   - If the send throws: roll the row back — `UPDATE run_queue SET queue_state='QUEUED', dispatched_at=NULL WHERE run_id=:id AND queue_state='DISPATCHED' AND cancel_requested=false` (if `cancel_requested` flipped meanwhile, go to `CANCELLED` instead). The row is retried next tick.

Claim-then-publish is chosen over publish-then-claim so that a concurrent cancel in the window sees `DISPATCHED` (not `QUEUED`) and correctly takes the cooperative path (§5) rather than marking a run `CANCELLED` that the Worker is already about to run.

**Idempotency.** The claim `UPDATE` is conditional on `queue_state='QUEUED'`; a dispatcher that re-scans the same row after a partial run finds it `DISPATCHED` and does nothing. A redelivered `runs.requested` (from any cause) is absorbed by the Worker's `worker.execution_attempt` claim (ADR-003 §3) — a `COMPLETED` claim re-emits the cached terminal, a `RUNNING` claim under lease is skipped. So dispatch is at-least-once into an idempotent consumer, the pattern the whole system already uses.

**Residual window (deferred to 2D).** If the API process dies *after* the `DISPATCHED` `UPDATE` commits but *before* the `send()` call, the row is `DISPATCHED` with `test_runs.status='PENDING'` and no `runs.requested` on the topic — a stranded run. This is the same class of gap as ADR-002 §Risks ("publish-after-commit … strands the run at PENDING. Accepted for 2A; transactional outbox is a later exercise"), one state later. `dispatched_at`, `dispatch_attempts`, and `queue_state='DISPATCHED'` + `test_runs.status='PENDING'` are recorded precisely so **2D's stuck-run reaper** (which already owns the ADR-002/003 "stuck RUNNING" reaper) can find and re-publish these rows. A `dispatch_attempts` ceiling (`dispatch-max-attempts`, default 5) moves a row that repeatedly fails to send to `queue_state='FAILED'` with a metric, so a poison row cannot re-publish forever.

#### 3.5 Queue maintenance

`QueueMaintenanceService` — a `@Scheduled` prune (mirroring ADR-003's `AttemptRetentionSweeper` and ADR-005's `ArtifactStagingSweeper`):

- `run_queue` rows in `COMPLETED|FAILED|CANCELLED` older than `qualityops.scheduling.queue.retention` (default **90d**) are deleted. (`requested_event_json` is already nulled at terminal, so the bulk is reclaimed immediately.) `GET /api/v1/runs` LEFT-JOINs `run_queue`, so a run older than 90d simply renders `queueState = null` — documented and acceptable.
- `schedule_fire` rows older than `qualityops.scheduling.fire-ledger-retention` (default **30d**) are deleted (a slot that old cannot be re-fired — `next_fire_at` has long moved on).

### 4. Priorities and per-tenant concurrency, enforced at dispatch

#### 4.1 Priority with an aging function against starvation

`priority ∈ {HIGH, NORMAL, LOW}`, default `NORMAL`. `HIGH` may only be set by `OWNER`/`ADMIN` (SpEL guard on `POST /api/v1/runs` and the schedule endpoints — §1.6). `LOW` is open to anyone. Requesting `HIGH` without the role ⇒ `403 FORBIDDEN` (explicit; the request's intent is not silently downgraded).

The dispatch scan orders by an **effective priority** that ages waiting rows upward, computed in the `ORDER BY` as a single SQL expression:

```sql
ORDER BY (
    CASE priority WHEN 'HIGH' THEN 20 WHEN 'NORMAL' THEN 10 ELSE 0 END
  + LEAST(:aging-max-boost,
          FLOOR(EXTRACT(EPOCH FROM (now() - enqueued_at)) / :aging-step-seconds))
) DESC, enqueued_at ASC
```

- `aging-step-seconds` (config `qualityops.scheduling.queue.aging-step`, default **60s**): every full minute of waiting adds 1 to effective priority.
- `aging-max-boost` (default **20**): a `LOW` row waiting ~20 min reaches effective 20, i.e. it competes with a freshly enqueued `HIGH`. It never *starves* a `HIGH`; it *catches up*.

Chosen over a **reserved per-priority slot ratio** (needs per-priority slot accounting in the dispatcher and a policy for unused reservations) and over **"every Nth pull ignores priority"** (bursty, coarse, hard to reason about). The aging function is one tunable expression, monotonic, and cheap.

#### 4.2 Per-tenant concurrency, counted at dispatch

- `max-active-runs-per-org` — config `qualityops.scheduling.queue.max-active-runs-per-org` (default **5**), overridable per org via `org_run_concurrency (org_id PK, max_active_runs, updated_at, created_at)`. **2C ships the table and the read path only**; the write API/UI is 2D+.
- "Active" for org O = `COUNT(run_queue WHERE org_id = O AND queue_state IN ('DISPATCHED','RUNNING'))` — the exact query in §3.4 step 1.
- The dispatcher walks the priority-ordered candidate list once, per §3.4 step 4, dispatching a row only while `active[org] + dispatchedThisTick[org] < limit[org]`. When a flooding org hits its cap, its remaining candidates are skipped and later candidates from other orgs are still served in the same batch — fair round-robin without an explicit round-robin data structure.
- **Hard cap under normal operation, soft under lock failure.** The `queue-dispatch` ShedLock lock serialises the dispatch scan to one replica at a time, so count-then-dispatch is single-writer within a tick and the cap holds exactly. The only way to exceed it is if the lock is bypassed (the lock store hands out the lock while the previous holder is still running past `lockAtMostFor`); then two dispatchers could each read `active = 4, limit = 5` and each dispatch, overshooting by up to `dispatch-batch-size`. Mitigation: generous `lockAtMostFor`; the overshoot self-corrects on the next tick (new dispatches are gated until `active` drops). Documented.
- Config keys: `qualityops.scheduling.queue.{max-active-runs-per-org, dispatch-batch-size (default 50), dispatch-interval (PT2S), aging-step (PT1M), aging-max-boost (20), dispatch-max-attempts (5), send-timeout (PT10S), retention (P90D)}`, `qualityops.scheduling.{tick-interval (PT15S), tick-batch-size (200), fire-ledger-retention (P30D)}`.

Priority and org limits are computed in the API **before** a single `runs.requested` is published. The Worker never sees either.

### 5. Queued-run controls and the cancel signal

#### 5.1 Listing

`GET /api/v1/runs` gains an optional `?queueState=QUEUED|DISPATCHED|RUNNING|COMPLETED|FAILED|CANCELLED` filter. The list query LEFT-JOINs `run_queue` (so pre-2C runs with no queue row still appear). `RunResponse` gains three nullable fields — `queueState`, `priority`, `cancelRequested` — null for pre-2C runs. One endpoint, one resource; a dedicated `/api/v1/runs/queued` is rejected (splits the resource, duplicates paging/filtering).

#### 5.2 Cancel

`POST /api/v1/runs/{id}/cancel` — `@PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")` (VIEWER cannot). An action sub-resource, consistent with `/schedules/{id}/pause`; documented as a deliberate REST exception (there is no idempotent noun for "cancel"). The handler reads the `run_queue` row `FOR UPDATE` and branches on `queue_state`:

| State | Action | Response |
|---|---|---|
| `QUEUED` | `UPDATE run_queue SET queue_state='CANCELLED', cancel_requested=true, cancel_requested_at=now(), terminal_at=now() WHERE run_id=? AND org_id=? AND queue_state='QUEUED'`; if 1 row, `UPDATE test_runs SET status='CANCELLED', completed_at=now(), started_at=COALESCE(started_at,now()) WHERE id=? AND org_id=? AND status='PENDING'`. **No Kafka. No Worker.** The dispatcher's `WHERE queue_state='QUEUED'` guarantees it is never picked. | `200` + run (`queueState=CANCELLED`) |
| `DISPATCHED` or `RUNNING` | `UPDATE run_queue SET cancel_requested=true, cancel_requested_at=now() WHERE run_id=? AND org_id=? AND queue_state IN ('DISPATCHED','RUNNING')`; publish `RunCancelRequestedEvent` to `runs.cancel` (key = `runId`). | `202` + run (`cancelRequested=true`, `queueState` unchanged) |
| `COMPLETED` / `FAILED` / `CANCELLED`, or no `run_queue` row | reject | `409 RUN_NOT_CANCELLABLE` |

**`CANCELLED` is reserved for runs that never executed.** A run cancelled while `QUEUED` becomes `test_runs.status='CANCELLED'` / `run_queue='CANCELLED'`. A run cancelled while `DISPATCHED`/`RUNNING` terminates through the **normal** lifecycle (`runs.completed` / `runs.failed`); its final `test_runs.status` is whatever the Worker reports (`PASSED`/`FAILED`), and the cancellation attempt is visible only via `run_queue.cancel_requested` / `cancel_requested_at`. We do not retro-label an executed run as `CANCELLED` — it *did* run, and runs are immutable. This keeps a clean invariant and needs **no enum change**: `CANCELLED` already exists in `RunStatus` (Java) and the `run_status` PostgreSQL enum (V6).

#### 5.3 The cancel event — standalone, not in the `RunEvent` seal

`packages/shared-events` gains:

```java
public record RunCancelRequestedEvent(
        UUID eventId, UUID correlationId, UUID orgId, UUID runId, UUID executionId,
        Instant occurredAt, int schemaVersion            // SCHEMA_VERSION = 1
) { public static final int SCHEMA_VERSION = 1; }
```

It is **not** added to `sealed interface RunEvent permits {RunRequested,RunStarted,RunCompleted,RunFailed}`, for the same reasons ADR-005 kept `ResultChunkEvent` out: (a) the seal exists so a *lifecycle* dispatcher can `switch` exhaustively over run **state transitions** (past-tense facts); a cancel request is an **imperative command**, not a transition; (b) a fifth permitted type would force every exhaustive `switch` in both apps to change; (c) it is consumed by one dedicated single-purpose listener, never dispatched polymorphically. New topics: `runs.cancel` (`@Bean NewTopic` on the API producer side) and `runs.cancel.DLT` (on the Worker consumer side). `@EmbeddedKafka` topic lists in both apps' ITs grow by these two.

#### 5.4 Wiring the Worker's `CancellationToken`

- **`RunCancelConsumer`** (new, `apps/worker` `execution/adapter/in/messaging/`, `@KafkaListener(topics="runs.cancel", groupId="worker-execution")`) records the cancellation in a new bounded in-memory `CancellationRegistry` — a `ConcurrentHashMap<UUID,Instant>` keyed by `executionId`, capped (`cancel-registry-max`, default 10 000, oldest evicted) and TTL-swept (entries older than the run wall-clock budget are useless). **No persistence, no `worker` schema change.**
- **`RunExecutionService.processRunRequested`**: the call site `var token = CancellationToken.never();` becomes `var token = () -> registry.isCancelled(event.executionId());`. The token is already polled between cases in `runCases` and passed into `CaseExecutionContext` → the runner (ADR-003 §4 cooperative points; ADR-004 checks it once before submitting the browser future). A `runs.cancel` that lands mid-run flips the registry; the next between-cases check turns every remaining case into `errorResult(c, "run cancelled")`, and the run still publishes `runs.completed` (aggregate `FAILED`) — unchanged `runCases` behaviour.
- **Pre-claim check**: immediately before `store.claim(...)`, if `registry.isCancelled(event.executionId())` is already true (the `runs.cancel` was consumed before this `runs.requested` — possible, different topics in the same group), the Worker still **claims** (to own the attempt and cache a terminal for dedup) and immediately publishes `runs.failed` with the generic reason `"execution cancelled before start"`. `test_runs` → `FAILED`, `run_queue` → `FAILED`, run terminal, loop closed deterministically. It does **not** silently drop the event (which would strand the run).
- **This is best-effort.** The `runs.cancel` may not have been consumed when `runs.requested` is processed. **The only fully-guaranteed cancellation is `QUEUED` → `CANCELLED`** (the API never publishes `runs.requested`). A `DISPATCHED`/`RUNNING` cancel is cooperative and may not take effect before the run completes normally — stated in the API response (`202`, not `200`) and in the docs. The Worker cannot consult `run_queue.cancel_requested` (no `run_queue` access — ADR-003 §3); the Kafka topic is the only channel.

### 6. Queue observability — Micrometer only

`QueueMetrics` registers meters on the existing Actuator surface (this ADR adds `micrometer-registry-prometheus` to `apps/api` and exposes `metrics,prometheus` alongside `health,info`). Gauge values that need a query are refreshed into `AtomicLong`s by a lightweight `@Scheduled` (every 10 s) so the gauge read is O(1):

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `qualityops.queue.depth` | gauge | `priority` | `QUEUED` count per priority. **No `org` tag** (unbounded cardinality) — global + per-priority only |
| `qualityops.queue.oldest_age_seconds` | gauge | — | `now() - min(enqueued_at)` over `QUEUED` |
| `qualityops.queue.wait_seconds` | timer (histogram) | — | `dispatched_at - enqueued_at`, recorded per dispatch |
| `qualityops.queue.dispatch_throughput` | counter | — | +1 per successful dispatch |
| `qualityops.queue.active_runs` | gauge | — | global `DISPATCHED`+`RUNNING` count (no `org` tag — cardinality; per-org top-N is a 2D/Grafana concern) |
| `qualityops.queue.cancellations` | counter | `phase` (`queued`\|`dispatched_running`) | |
| `qualityops.schedule.fires` | counter | `outcome` (`fired`\|`deduped`\|`skipped_missed`\|`caught_up`\|`abandoned`) | |
| `qualityops.scheduling.tick_duration`, `qualityops.queue.dispatch_duration` | timer | — | explicit `Timer.record(...)` around each job (AOP `@Timed` is 2E) |
| `qualityops.scheduling.leader` | gauge | `job` | 1 while this instance holds the lock, else 0 (best-effort) |

**Kafka consumer lag** for `worker-execution` / `api-results` is exposed by Spring Boot's auto-configured `KafkaClientMetrics` binding of the native consumer metrics (`kafka.consumer.fetch.manager.records.lag` / `records-lag-max`) — present once `spring-boot-starter-actuator` + a `MeterRegistry` are on the consumer, which they now are on `apps/api` and already are on `apps/worker`. If a gap is found in practice, the fallback is a `DefaultKafkaConsumerFactory` customizer adding `MicrometerConsumerListener` — noted, not implemented preemptively.

No `GET /api/v1/admin/queue` summary endpoint, no Grafana dashboard JSON — both are 2D.

## Consequences

### Positive

- One-time and recurring (cron, IANA time zone, DST-correct) schedules with pause/resume, catch-up policy, and next-fire preview, firing **at most once per logical occurrence** across any number of API replicas (`schedule_fire` unique ledger) and any number of retried ticks.
- Two API replicas coordinate through a boring, documented library (ShedLock) and one PostgreSQL table; a lock-store outage degrades to "nothing fires", never to "fires twice".
- The queue is authoritative in PostgreSQL; Kafka carries only the frozen, immutable job. Priority (with anti-starvation aging) and per-tenant concurrency are enforced in the API before publication — the Worker stays one-topic, one-group, priority-agnostic, unchanged.
- A run cancelled while `QUEUED` **provably never executes** (the API never publishes `runs.requested`); a run cancelled later is cooperatively cancelled through the `CancellationToken` the codebase was already built for, closing ADR-003 §4.
- `RunLifecycleService` advances `run_queue` idempotently by gating each queue `UPDATE` on the existing `executionId`-guarded `test_runs` `UPDATE` — no new guard column, redelivery-safe.
- The dispatcher's mini-outbox (`requested_event_json`) guarantees the on-wire job is byte-stable with the run and makes the dispatcher a re-map-free pass-through.
- Queue depth, wait time, dispatch throughput, oldest-job age, active runs, cancellations, schedule fires, and consumer lag are all on the standard Prometheus surface with bounded cardinality.
- Every new decision is additive and reversible: four append-only migrations, one new module, two `@Scheduled` beans, one standalone event, one in-memory Worker component.

### Negative

- `RunService.trigger` **no longer publishes `runs.requested`** — it enqueues, and the dispatcher publishes ~`dispatch-interval` later. Every integration test that asserts synchronous publication on `POST /api/v1/runs` must change to await the dispatcher (or run with a short `dispatch-interval` / call `dispatchAvailable()` directly). This is the single largest ripple.
- Four new tables (`shedlock`, `run_queue`, `schedule` + `schedule_fire`, `org_run_concurrency`), a new module with full hexagonal layout, two `@Scheduled` jobs, a maintenance sweeper, and a new dependency (`shedlock-spring` + jdbc provider) plus `micrometer-registry-prometheus`.
- `apps/api` becomes a scheduler host (`@EnableScheduling`) — a new operational concern (leader lock health, tick drift, pool sizing).
- A second API replica must exist somewhere for the leader smoke; adding `api-2` to compose is heavier on an already-loaded dev machine (mitigated — the authoritative proof is an IT, §appendix).
- `RunResponse` widens by three nullable fields; the frontend `RunStatus`/run types need an additive `queueState`/`priority` (frontend work is out of scope this increment but the contract must stay compatible).
- One more Kafka topic pair (`runs.cancel` / `.DLT`) and a new consumer in the Worker.

### Risks

- **Tick/dispatch drift and long jobs vs `lockAtMostFor`.** A job that runs longer than `lockAtMostFor` can be double-run by a second replica. Mitigation: `lockAtMostFor` is minutes against sub-second real runtime; `tick-batch-size` / `dispatch-batch-size` bound per-invocation work; `qualityops.scheduling.tick_duration` / `dispatch_duration` timers surface creep.
- **The `DISPATCHED`-but-`send()`-lost window** (crash between the claim commit and the publish) strands a run. Deferred to 2D's stuck-run reaper; `dispatched_at` / `dispatch_attempts` / `queue_state='DISPATCHED'` + `test_runs.status='PENDING'` are recorded so the reaper can find it; `dispatch-max-attempts` bounds a poison row.
- **Cooperative cancel does not stop a mid-execution run.** A `DISPATCHED`/`RUNNING` cancel may land after the last case; the run completes `PASSED`/`FAILED`. The `202` response and the docs state this. Forceful kill is 2D+.
- **Per-org concurrency is a soft cap if the ShedLock lock is bypassed** — transient overshoot by up to one batch, self-correcting next tick (§4.2).
- **Cron DST transitions.** Spring-forward gaps roll forward; fall-back overlaps fire once. Pinned by `CronCalculatorTest`. One-time `fire_at` is stored as an absolute instant (no zone ambiguity).
- **`schedule_fire` / `run_queue` growth.** Bounded by the `@Scheduled` prune (`fire-ledger-retention` 30d, queue `retention` 90d) and by nulling `requested_event_json` at terminal.
- **Rolling-deploy skew.** Old API + new API replica: both run the scheduler; ShedLock still serialises. New API + old API replica during rollout: the old replica still publishes on `trigger` *and* writes no `run_queue` row → its runs bypass admission control until it is replaced. Deploy the API fleet together (as ADR-002/003/004/005 already require); package names are unchanged so there is no DLT storm.
- **`RunCancelRequestedEvent` to an old Worker** (skew) is ignored (no consumer) → cancel silently no-ops; the `QUEUED` guarantee is unaffected because that path never uses Kafka.

## Alternatives considered

### Scheduling

- **Quartz / `spring-boot-starter-quartz`** — rejected: a second scheduler, thread pool, and jobstore (11 tables), plus DB-row-lock clustering that duplicates ShedLock, for cron expressiveness we do not need.
- **`cron-utils`** — rejected: an extra dependency whose unique value (descriptions, Quartz-syntax) is not needed now; the swap-in if 2D's CI API must accept Quartz crons.
- **Compute `next_fire_at` live in the tick query** (parse cron every tick) — rejected: turns the hot admission query into a per-row cron parse. Materialising an absolute `TIMESTAMPTZ` keeps it an index range scan.
- **A generic transactional outbox for `runs.requested`** — rejected as scope creep; the mini-outbox column covers exactly the one hop 2C needs. Full outbox stays a Phase-7 exercise.

### Leader coordination

- **Redis `SET NX` lock** — rejected: `ARCHITECTURE.md` decision #3 defines Redis as ephemeral; a flush could let two schedulers fire. ShedLock+Postgres is durable and is what the ROADMAP/PLAN name.
- **Kubernetes Lease** — rejected for 2C: no K8s until Phase 5, and it is not exercisable in compose or Testcontainers. ROADMAP already earmarks a Lease-vs-JDBC comparison for Phase 5.
- **Hand-rolled `pg_advisory_lock`** — works, but ShedLock adds TTL/auto-expiry, `usingDbTime()`, `lockAtLeastFor`, and a documented operational story for free.
- **Per-schedule ShedLock locks** — rejected: the `schedule_fire` ledger and the conditional dispatch claim already make double-fire/double-dispatch impossible; per-schedule locks add N rows and N acquisitions per tick for no correctness gain.

### Queue

- **Columns on `test_runs` instead of a `run_queue` table** — rejected: pollutes the immutable run aggregate (domain rule #2) with a churning lifecycle and burdens every run-history read.
- **PostgreSQL `ENUM` types for `priority` / `queue_state`** (as `run_status` in V6) — rejected: queue states will evolve across 2C/2D; `VARCHAR + CHECK` is transaction-safe to alter and needs no Hibernate named-enum mapping.
- **Priority Kafka topics `runs.requested.high|normal|low`** (PLAN §2C wording) — rejected: pushes priority logic to the broker/consumer, complicates DLT and co-partitioning with the lifecycle topics, and cannot express aging or per-tenant fairness. A DB-ordered dispatcher can, and the Worker stays priority-agnostic on one topic.
- **Publish-then-claim** in the dispatcher — rejected: a concurrent cancel in the window would see `QUEUED`, mark `CANCELLED`, and yet the Worker already has the event — violating the `QUEUED`-cancel guarantee. Claim-then-publish keeps the guarantee; the stranded-row window is the accepted, deferred ADR-002-class gap.

### Cancellation

- **A `runs.cancelled` lifecycle event inside the `RunEvent` seal** — rejected: forces every exhaustive lifecycle `switch` in both apps to change; a cancel is a command, not a transition. Standalone `RunCancelRequestedEvent`, mirroring `ResultChunkEvent`.
- **Worker consults `run_queue.cancel_requested` at claim time** — rejected: the Worker has no `run_queue` access (ADR-003 §3) and must not gain one. The Kafka topic is the only channel.
- **A durable Worker-side cancellation table** — rejected: no `worker` migration this increment; a bounded in-memory registry is sufficient because a redelivered/lease-stolen execution restarts all cases anyway and the `QUEUED`-cancel path (the only hard guarantee) never involves the Worker.
- **Retro-labelling an executed-then-cancelled run as `CANCELLED`** — rejected: it did execute (partially or fully); runs are immutable and the record should be honest. `CANCELLED` is reserved for never-executed runs; `cancel_requested`/`cancel_requested_at` carry the intent for the rest.

### Observability

- **A `GET /api/v1/admin/queue` summary endpoint and Grafana JSON now** — rejected: 2D owns both. 2C registers Micrometer meters only.
- **Per-org tags on queue gauges** — rejected: unbounded cardinality. Global + `priority` only; per-org top-N is a later, bounded concern.

## Amendment: 2C design-point resolutions & audit follow-ups (2026-09-02)

Gap-closure only — no schema change; V12–V15 unchanged.

**Implementation & verification status — 2C COMPLETE (2026-09-03).** Architect audit
→ planner → implementer → reviewer cycle done; all reviewer must-fix findings
landed. Verified green: `mvn -B -ntp verify` across `packages/shared-events`,
`apps/api`, `apps/worker`, `apps/gateway` (Testcontainers ITs incl.
`SchedulingTickIT`, `RunCancellationIT`, `QueueDispatchFailureIT`,
`QueueDispatchCancelRaceIT`, `QueueMetricsIT`/`QueueMetricsRefresherIT`,
`RunOrchestrationKafkaIT`; `@Tag("browser")` worker ITs excluded — unaffected by 2C);
`apps/web` lint + typecheck + vitest + build; a full `docker compose up` stack +
the Playwright login→suite→run→results smoke.

1. **Design point 1 — tenant scoping.** `schedule`, `schedule_fire` and `run_queue`
   all carry `org_id`, and every scheduling-module and dispatcher tenant query
   filters by it. `org_run_concurrency` deliberately keys on `org_id UUID PRIMARY
   KEY` (B9) — a documented deviation from the surrogate-id migration rule, because
   the row *is* the per-tenant setting. `shedlock` is the sole `org_id`-free table
   (pure infra coordination, like `flyway_schema_history`); it has no JPA entity
   and is never read on a request path.

2. **Design point 2 — leader coordination (B6) + fire-slot edge (B3).**
   `qualityops.scheduling.leader{job}` is now set to 1 at the top of each locked
   job body (`QueueDispatchJob.dispatch`, `ScheduleTickJob.tick`) and back to 0 in
   a `finally` — faithful, because `@SchedulerLock` only runs the body under the
   lock. B3: an on-time RECURRING fire stores `fire_slot = next_fire_at` and a
   ONE_TIME fire stores `fire_slot = fire_at` as byte-stable columns, so
   `UNIQUE(schedule_id, fire_slot)` dedup is exact. Only the `FIRE_ONCE` catch-up
   slot is wall-clock-derived (`CronCalculator.previousOccurrence`) and is
   therefore theoretically racy within one cron-occurrence width if a ShedLock
   bypass coincides with an already-missed schedule — accepted, bounded to one
   extra make-up run.

3. **Design point 3 — priority / fairness / cancellation (B1 + B2).**
   B1: on a failed publish the dispatcher's `run_queue` terminal/rollback write
   (`markDispatchFailed` for corrupt `requested_event_json` or a send failing at
   `dispatch-max-attempts`; the rollback-to-CANCELLED branch when a cancel was
   requested in the send window) **and** the matching `test_runs` PENDING→terminal
   reconciliation (`PENDING→FAILED`, or `PENDING→CANCELLED` with
   `requested_event_json` nulled) are committed **atomically in a single
   `TransactionTemplate` unit** inside `QueueDispatchService.reconcileAfterFailedPublish`
   (each write still a guarded `id` + `org_id` + `status='PENDING'` UPDATE). The
   class stays non-`@Transactional`; only the claim + synchronous publish path is
   left un-wrapped (its commit-then-publish ordering is load-bearing). A crash
   mid-reconcile therefore leaves both rows in their pre-reconcile state
   (`queue_state='DISPATCHED'` + `test_runs.status='PENDING'`) — never a
   queue-terminal + run-PENDING split. That single remaining stranded state — the
   crash between the dispatch-claim commit and the `send()` call — is deferred to
   the 2D reaper (item 6). B2: the four dispatch/cancel interleavings —
   (i) cancel reads QUEUED first → `cancelQueued` 1 row → 200 CANCELLED, no Kafka;
   (ii) dispatcher claims first → `cancelQueued` 0 rows → cancel re-reads, sees
   DISPATCHED, falls through to cooperative → 202 + `RunCancelRequestedEvent`;
   (iii) cancel reads DISPATCHED/RUNNING → cooperative, `requestCancel` 1 row → 202;
   (iv) run already terminal → `requestCancel` 0 rows → 409 `RUN_NOT_CANCELLABLE`
   (genuinely terminal only). No spurious 409; `requestCancel` returns its row
   count and `rollbackDispatch` returns `REQUEUED | CANCELLED | NOOP`. The cancel
   handler (`RunCancellationService`) uses a **plain read** of the `run_queue` row
   plus these guarded conditional UPDATEs with a fall-through re-read — race-correct
   without a lock. This **supersedes the literal "reads the `run_queue` row `FOR
   UPDATE`" wording in §5.2**. The QUEUED path commits its `run_queue` + `test_runs`
   writes atomically in one `TransactionTemplate` unit; the cooperative path
   commits the single `requestCancel` UPDATE first and publishes
   `RunCancelRequestedEvent` **after** that commit (commit-then-publish, matching
   `QueueDispatchService` and `.claude/rules/kafka-events.md`) — so
   `RunCancellationService` is no longer class-level `@Transactional`.

4. **Design point 4 — `RunCancelRequestedEvent`.** `SCHEMA_VERSION = 1`, a
   standalone record deliberately NOT a `permits` of the sealed `RunEvent`
   (imperative command, not a past-tense transition — keeps every exhaustive
   `switch` in api+worker unchanged), mirroring `ResultChunkEvent`. Topic pair
   `runs.cancel` / `runs.cancel.DLT`. An old Worker with no consumer silently
   ignores it and the QUEUED-cancel guarantee is unaffected (that path never uses
   Kafka). `EventContractTest` locks the field set, `schemaVersion == 1`, and the
   seal non-membership.

5. **Design point 5 — observability (B5) + meter row.** No queue/schedule meter
   carries an `org` tag (tags are `priority` / `phase` / `outcome` / `job` /
   `reason` only). `QueueMetrics.refresh()` has NO `@SchedulerLock` by design —
   Prometheus gauges are per-scrape-target, so each replica refreshes its own
   `AtomicLong`s (3 indexed aggregates / replica / 10s). Its 10s trigger now lives
   in a separate `QueueMetricsRefresher` gated on
   `qualityops.scheduling.jobs-enabled`, so jobs-disabled ITs are quiet while the
   gauges stay registered unconditionally. New §6 meter row:
   `qualityops.queue.dispatch_failed` — counter, tag
   `reason ∈ {attempts_ceiling, corrupt_event}`.

6. **Out of scope for 2C (deferred to 2D).** Jenkins/GitLab CI-trigger adapter;
   Caseflow OpenAPI; signed inbound webhooks; stuck-run / stranded-DISPATCHED
   reaper; queue-driven re-published retries; `org_run_concurrency` write API/UI;
   `GET /api/v1/admin/queue` summary + Grafana JSON; `results.chunk` WebSocket push
   (2E).
