# ADR-007: Stuck-run reaper, queue-driven retry, CI execution API, and the Caseflow contract

## Status

Proposed.

- **Extends** ADR-006 §3.4 (closes the "residual window … deferred to 2D" — the `DISPATCHED`-but-`send()`-lost stranded row) and ADR-006 §4.2 (adds the `org_run_concurrency` **write** path that 2C shipped read-only). **Extends** ADR-002 §Risks / ADR-003 §4 ("stuck `RUNNING` with no reaper … deferred to 2D").
- **Preserves** ADR-002 §1 — the API stays the sole writer of authoritative run/result state. **The Worker gets no new DB reach, no migration, no new topic, and no code change.** The reaper and queue-driven retry both re-publish to the existing `runs.requested`; completion webhooks are HTTP, not Kafka.
- **Does not touch `packages/shared-events`.** No new event record, no `SCHEMA_VERSION` bump, nothing added to the `sealed interface RunEvent`. `RunFailedEvent` already means "execution errored, not a test verdict"; that is the signal queue-driven retry keys on.
- **Preserves** the ADR-006 dispatcher invariants: admission control lives entirely in the API before any `runs.requested` is published; claim-(commit)-then-publish ordering is load-bearing; a `QUEUED` cancel provably never executes.
- Realises `docs/product/PHASE-2-PLAN.md` §2D. It **narrows** that plan text in three places: the CI idempotency table is **V17** (not `V11`, long taken); `RunCancellationService` and queued-run list/cancel **already landed in 2C** (ADR-006 §5), so 2D's cancel work is limited to the reaper's stranded/stuck reconciliation; and the idempotency-key store is a dedicated `ci_idempotency_key` table (not `common/IdempotencyKeyStore.java` as a generic component).

## Context

After Phase 2C the run path is: `RunEnqueueService.enqueue` (the single admission point) validates the target, freezes the snapshot, mints `executionId`, inserts `test_runs` PENDING + `run_queue` QUEUED with the fully-serialised `RunRequestedEvent` in `requested_event_json`, and **publishes nothing**. `QueueDispatchJob` (`@Scheduled` + ShedLock `queue-dispatch`) selects `QUEUED` candidates by aged effective priority, enforces per-org concurrency, then **claims (commit) → publishes `runs.requested` synchronously**. The Worker consumes it (group `worker-execution`), durably claims `worker.execution_attempt`, executes, and publishes `runs.started` then one of `runs.completed` / `runs.failed`. `RunLifecycleService` (group `api-execution`) applies the org- + `executionId`-guarded `test_runs` transition and, gated on that having moved a row, advances `run_queue`.

Six capabilities remain, all named for 2D:

1. **Stuck-run reaper.** Two failure modes have no recovery. **(a)** ADR-006 §3.4's explicitly-deferred window: the API crashes *after* the dispatch-claim `UPDATE` commits (`run_queue.queue_state='DISPATCHED'`, `dispatched_at` set, `dispatch_attempts` bumped) but *before* `send()` — `runs.requested` is never on the topic, `test_runs.status` stays `PENDING`, and nothing retries. **(b)** The ADR-002/ADR-003 "stuck `RUNNING`" gap: the Worker crashes after `runs.started` and before a terminal, or a dispatched run never produces any lifecycle event — the run sits `RUNNING` (or `PENDING`) forever.
2. **Queue-driven retry.** ADR-005's bounded in-run retry re-runs a transient case *inside one `processRunRequested` call*. There is no retry for a run whose **whole execution** errored (`runs.failed` — a harness/infra fault, distinct from a test `FAILED` which rides `runs.completed`). PHASE-2-PLAN §2B deferred "re-published `runs.requested` retry" to 2C, and 2C deferred it to here.
3. **`org_run_concurrency` write path.** 2C shipped the V15 table and the dispatcher read path with a global default only. There is no way for an org admin to raise or lower their own cap.
4. **Queue summary endpoint + Grafana.** 2C registered Micrometer meters but explicitly left `GET /api/v1/admin/queue` and the dashboard JSON to 2D.
5. **CI execution API.** CI systems have only `POST /api/v1/runs`, which is not idempotent — a pipeline retry double-triggers a run.
6. **Caseflow contract.** No versioned external contract, and no push notification when a run finishes — CI systems must poll.

Constraints carried from ADR-001…006: multi-tenancy on every row, event, and query; idempotency under Kafka at-least-once; runs are immutable once triggered (config snapshotted at trigger — domain rule #2); the API is the sole writer and never calls the Worker synchronously; boring, reversible technology; Flyway append-only from **V16**; every new table carries `org_id` (there is no infra-coordination-only table this increment, unlike V12 `shedlock`); commit-then-publish for anything touching Kafka; `apps/web` stays contract-compatible (additive only — no frontend work in 2D).

Two invariants shape the design:

- **The reaper and queue-driven retry are recovery mechanisms layered on top of the existing guards — they add no new authority.** Every reaper and retry write is an org-scoped, conditionally-guarded `UPDATE`/`INSERT` that is a no-op if a real lifecycle event raced it. Re-publishing `runs.requested` is safe because the Worker's `worker.execution_attempt` claim (ADR-003 §3) already absorbs duplicate delivery.
- **A redelivered `runs.failed` must not spawn a second retry.** The `executionId` guard on `transitionToTerminal` already makes the second delivery a no-op; queue-driven retry is gated on that `moved` boolean, inheriting the guarantee without a new column.

## Decision

### 1. Stuck-run reaper — one ShedLock-locked `@Scheduled` job, two guarded reconciliations

#### 1.1 The job

`com.qualityops.api.execution.application.scheduler.StuckRunReaper` — a `@Component`, `@ConditionalOnProperty("qualityops.scheduling.jobs-enabled")` (matchIfMissing true), `@Scheduled(fixedDelayString = "${qualityops.scheduling.reaper.interval:PT60S}")`, `@SchedulerLock(name = "stuck-run-reaper", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")`. It sets `metrics.leaderHeld("stuck-run-reaper", true)` at the top of the body and `false` in a `finally` (faithful — `@SchedulerLock` only runs the body under the lock), wraps the body in `Timer` → `qualityops.scheduling.reaper_duration`, and delegates to `StuckRunReaperService.sweep()`.

`com.qualityops.api.execution.application.service.StuckRunReaperService` — **not** `@Transactional` at the class level (mirrors `QueueDispatchService` / `RunCancellationService`: the claim `UPDATE` must commit before the re-publish). `sweep()` is a public method also invoked directly by ITs (like `QueueDispatchService.dispatchAvailable()` in 2C). It runs reconciliation (a) then (b), each over its own `FOR UPDATE SKIP LOCKED` candidate batch (`qualityops.scheduling.reaper.batch-size`, default 100), so two API replicas never fight over the same row and the ShedLock lock only stops redundant scans.

New leader-gauge value: `qualityops.scheduling.leader{job="stuck-run-reaper"}`. `QueueMetrics.leaderHeld` switches from its two-branch ternary to a small `Map<String,AtomicLong>` keyed by job name (also serves §6's `webhook-dispatch`).

#### 1.2 Reconciliation (a) — stranded `DISPATCHED` → re-publish `runs.requested`

**Decision: a new guarded re-dispatch `UPDATE` keyed on `queue_state='DISPATCHED' AND dispatched_at < :graceCutoff`, not `claimForDispatch` semantics.** `claimForDispatch` is `WHERE queue_state='QUEUED'` and would never match a stranded row; giving it a second predicate would overload the dispatcher's hot path. A dedicated method keeps the two claim predicates independent and self-documenting.

New `RunQueueRepository` port methods (native, PG-specific, in `RunQueueRepositoryAdapter` via `RunQueueJpaRepository`):

```java
/** DISPATCHED + test_runs PENDING + grace<age<timeout, oldest first, FOR UPDATE SKIP LOCKED. */
List<DispatchCandidate> selectStrandedDispatched(Instant graceCutoff, Instant timeoutCutoff, int batch);

/** Re-claim a stranded row: advance dispatched_at + bump attempts. Guarded so a
 *  concurrent cancel (cancel_requested=true) or a real transition is not clobbered.
 *  @return true iff a DISPATCHED, not-cancel-requested, still-stale row moved. */
boolean reclaimStranded(UUID runId, Instant graceCutoff);
```

Selection SQL:

```sql
SELECT rq.run_id, rq.org_id, rq.priority, rq.enqueued_at, rq.dispatch_attempts, rq.requested_event_json
FROM run_queue rq JOIN test_runs tr ON tr.id = rq.run_id
WHERE rq.queue_state = 'DISPATCHED'
  AND tr.status = 'PENDING'
  AND rq.cancel_requested = FALSE
  AND rq.dispatched_at <  :graceCutoff     -- now() - reaper.dispatch-grace  (default PT2M)
  AND rq.dispatched_at >= :timeoutCutoff   -- now() - reaper.run-timeout     (older ⇒ handled by (b))
ORDER BY rq.dispatched_at
LIMIT :batch
FOR UPDATE SKIP LOCKED
```

Re-claim SQL:

```sql
UPDATE run_queue
SET dispatched_at = now(), dispatch_attempts = dispatch_attempts + 1, last_dispatch_at = now()
WHERE run_id = :runId AND queue_state = 'DISPATCHED'
  AND cancel_requested = FALSE AND dispatched_at < :graceCutoff
```

Per candidate, `StuckRunReaperService` performs **exactly the same claim-publish-reconcile dance as `QueueDispatchService.dispatchOne`**, differing only in the claim call. To avoid duplicating `reconcileAfterFailedPublish`, that method and the corrupt-event / attempts-ceiling handling are extracted into a package-private `QueueDispatchService.publishClaimed(DispatchCandidate c, boolean alreadyClaimed)` reused by both. Flow:

1. `reclaimStranded(runId, graceCutoff)` — its own `@Transactional` adapter method, **commits**. `false` ⇒ a cancel raced or a real transition landed → go to the cancel branch below or skip.
2. Deserialise `requested_event_json` with the shared `ObjectMapper` (exactly as `QueueDispatchService.dispatchOne`). On `JsonProcessingException` → one `TransactionTemplate` unit: `runQueueRepository.markDispatchFailed(runId)` + `runRepository.transitionToFailed(runId, orgId)`; `metrics.reaped("reaper_error")`; `metrics.dispatchFailed("corrupt_event")`.
3. `runEventPublisher.publishRunRequested(event)` — synchronous, bounded by `qualityops.scheduling.queue.send-timeout` (10s). Success ⇒ `metrics.reaped("redispatched")`; the run proceeds normally (`RunLifecycleService` will move `DISPATCHED→RUNNING` on `runs.started`). Re-publish is **idempotent**: the Worker's `worker.execution_attempt` `INSERT … ON CONFLICT` absorbs a duplicate (a `COMPLETED` claim re-emits the cached terminal; a live `RUNNING` claim under lease is skipped).
4. On `RunEventPublishException`: if `c.dispatchAttempts() + 1 >= queue.dispatch-max-attempts` (reuse the existing ceiling, default 5) → one `TransactionTemplate` unit driving **both** `run_queue` (`markDispatchFailed`) **and** `test_runs` (`transitionToFailed`) to `FAILED`; `metrics.reaped("redispatch_exhausted")`; `metrics.dispatchFailed("attempts_ceiling")`. Otherwise leave the row `DISPATCHED` (its `dispatched_at` is now fresh, so it is not re-picked until the next grace window) and log; the next reaper pass retries.
5. **Cancel raced** (`reclaimStranded` returned `false` because `cancel_requested=true`): call the existing `runQueueRepository.rollbackDispatch(runId)` (its SQL already moves `DISPATCHED→CANCELLED` when `cancel_requested`, nulling `requested_event_json`) and, if it returns `CANCELLED`, `runRepository.transitionToCancelled(runId, orgId)` — the identical atomic pair from `QueueDispatchService.reconcileAfterFailedPublish`'s CANCELLED branch. `metrics.reaped("cancel_reconciled")`.

**Transaction boundaries for (a):** selection runs in the adapter's read-write tx (needs `FOR UPDATE SKIP LOCKED`); `reclaimStranded` is its own committed tx; the publish is outside any tx (commit-then-publish preserved); the corrupt-event / ceiling / cancel reconciliations are each one `TransactionTemplate` unit. A crash mid-reconcile leaves both rows in their pre-reconcile state (`DISPATCHED` + `PENDING`) — never a queue-terminal + run-`PENDING` split — and the next reaper pass finishes the job.

#### 1.3 Reconciliation (b) — stuck active run → `FAILED` in both tables, no Kafka

**Timestamp decision: no migration. Derive "no lifecycle event past timeout" from existing columns.** A stuck `RUNNING` run has `test_runs.started_at` (set on `runs.started`) and no `completed_at`; a dispatched run that never produced *any* lifecycle event has `test_runs.status='PENDING'` and `run_queue.dispatched_at`. Both are already present. Adding an `updated_at` to the immutable `test_runs` aggregate would violate domain rule #2's spirit and buys nothing here.

New `RunQueueRepository` method:

```java
/** DISPATCHED/RUNNING queue rows whose run has shown no lifecycle progress past
 *  run-timeout. FOR UPDATE SKIP LOCKED. */
List<StuckRun> selectStuckActive(Instant timeoutCutoff, int batch);   // StuckRun(runId, orgId, queueState)
```

```sql
SELECT rq.run_id, rq.org_id, rq.queue_state
FROM run_queue rq JOIN test_runs tr ON tr.id = rq.run_id
WHERE rq.queue_state IN ('DISPATCHED','RUNNING')
  AND ( (tr.status = 'RUNNING' AND tr.started_at   < :timeoutCutoff)
     OR (tr.status = 'PENDING' AND rq.dispatched_at < :timeoutCutoff) )
ORDER BY rq.dispatched_at
LIMIT :batch
FOR UPDATE SKIP LOCKED
```

New `RunRepository` method (guarded, org-scoped, **no `executionId`** — the reaper is driven by its own `test_runs` read, not a Worker event, so there is no attempt identity to match; the `status IN ('PENDING','RUNNING')` predicate is the whole guard):

```java
/** PENDING|RUNNING -> FAILED for a run the reaper has judged stuck. Sets
 *  completed_at, COALESCEs started_at. Silent no-op (0 rows) if a real terminal
 *  raced in. */
int reapToFailed(UUID runId, UUID orgId, Instant ts);
```

```sql
UPDATE test_runs
SET status = 'FAILED', completed_at = :ts, started_at = COALESCE(started_at, :ts)
WHERE id = :runId AND org_id = :orgId AND status IN ('PENDING','RUNNING')
```

Per stuck run, **one `TransactionTemplate` unit**:

```java
int rows = runRepository.reapToFailed(runId, orgId, now);
if (rows > 0) {
    runQueueRepository.transitionQueueState(runId, orgId,
        EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, /*terminal*/ true);
    metrics.reaped("stuck_failed");
} // else: a real runs.completed/failed won — logged no-op
```

The `run_queue` transition is gated on `rows > 0`, exactly as `RunLifecycleService` gates its queue write on the `test_runs` `UPDATE` — so it inherits the org guarantee and stays redelivery-safe. **No Kafka to the Worker** (there is no channel — ADR-003 §3; and a genuinely-wedged Worker will not react anyway). If the Worker later publishes a terminal for that run, `transitionToTerminal`'s `status IN (PENDING,RUNNING)` predicate finds 0 rows (already `FAILED`) → the existing logged no-op. **Redelivery-safe:** a second reaper pass finds the row already `FAILED` → `reapToFailed` returns 0 → no-op.

**Multi-tenancy:** every selection joins are unfiltered by org (the reaper is a platform sweep) but every *write* is `WHERE … AND org_id = :orgId` using the `org_id` carried on the candidate row, and the ITs assert a foreign-org stranded/stuck row is untouched when the reaper acts on another org's row.

#### 1.4 Config and metrics for the reaper

Config under `qualityops.scheduling.reaper.*`: `interval` (`PT60S`), `dispatch-grace` (`PT2M`), `run-timeout` (`PT30M`), `batch-size` (`100`). The attempts ceiling reuses `qualityops.scheduling.queue.dispatch-max-attempts` and the publish bound reuses `queue.send-timeout`.

Meters (bounded cardinality, **no `org` tag**):

| Meter | Type | Tags |
|---|---|---|
| `qualityops.queue.reaped` | counter | `kind ∈ {redispatched, redispatch_exhausted, stuck_failed, cancel_reconciled, reaper_error}` |
| `qualityops.scheduling.reaper_duration` | timer | — |
| `qualityops.scheduling.leader` | gauge (existing) | `job` gains the value `stuck-run-reaper` |

### 2. Queue-driven retry — a fresh `run_queue` row, same frozen snapshot, budgeted

#### 2.1 Classification: all `runs.failed` are retry candidates, minus a reason-prefix denylist

`RunFailedEvent` already means "execution itself errored — interrupt / infra / harness — **not** a test failure" (ADR-002 §2). A genuine test verdict never arrives on `runs.failed`; it rides `runs.completed` (which maps to `run_queue.COMPLETED` regardless of PASSED/FAILED). So the base rule is: **every `runs.failed` is retryable**, except reasons that are deterministically permanent. Rather than an allowlist that must be kept in sync with every Worker reason string, 2D uses a small **denylist of reason prefixes** — config `qualityops.scheduling.retry.non-retryable-reason-prefixes` (default `execution cancelled,run cancelled`) — matched case-insensitively against `RunFailedEvent.reason()`. This guarantees a cancelled run (pre-start cancel ⇒ `runs.failed("execution cancelled before start")`, ADR-006 §5.4) is **never** resurrected by a retry, and leaves room to add `unresolved secret reference` / `blocked:` if the Worker starts routing those to `runs.failed` (today they are `BLOCKED` cases on a `runs.completed`).

A `runs.completed` whose aggregate verdict is `FAILED` is **not** retried — that is a real (possibly flaky) test result; retrying it would mask regressions and inflate pass rates, exactly as ADR-005 §3.1 rejected for in-run retry.

#### 2.2 The hook point and idempotency argument

Retry is enqueued from **`RunLifecycleService.onRunFailed`, in the same `@Transactional` method, immediately after the guarded `test_runs` terminal transition, gated on its `moved` boolean**:

```java
@Override
public void onRunFailed(RunFailedEvent event) {
    boolean moved = runRepository.transitionToTerminal(
        event.runId(), event.orgId(), event.executionId(), RunStatus.FAILED, event.occurredAt());
    if (moved) {
        runQueueRepository.transitionQueueState(event.runId(), event.orgId(),
            EnumSet.of(QueueState.DISPATCHED, QueueState.RUNNING), QueueState.FAILED, true);
        retryRunUseCase.retryIfEligible(event.runId(), event.orgId(), event.reason());   // NEW
    } else {
        log.info("RunFailed for run {} exec {} — no-op", event.runId(), event.executionId());
    }
}
```

**Idempotency:** `transitionToTerminal` is guarded on `org_id + executionId + status IN (PENDING,RUNNING)`. The first `runs.failed` delivery moves the row (`moved == true`) and enqueues at most one retry. Every redelivery finds the run already `FAILED` → `moved == false` → the retry call is never reached. The retry enqueue joins the lifecycle handler's transaction, so the new `test_runs` + `run_queue` rows commit atomically with the terminal transition (or the whole handler rolls back and Kafka redelivers `runs.failed`, which then finds `moved == true` again only if the rollback undid the terminal too — consistent). No new guard column; the `moved` boolean *is* the dedup.

#### 2.3 `RetryRunService` and the budget

New input port `com.qualityops.api.execution.application.port.in.RetryRunUseCase`:

```java
public interface RetryRunUseCase {
    /** Enqueues a fresh retry run iff the reason is retryable and both budgets
     *  have room. No-op otherwise. Runs inside the caller's transaction. */
    Optional<EnqueueRunResult> retryIfEligible(UUID failedRunId, UUID orgId, String failureReason);
}
```

implemented by `com.qualityops.api.execution.application.service.RetryRunService` (`@Transactional(propagation = REQUIRED)` — joins `RunLifecycleService`'s tx). It:

1. **Reason check** — `failureReason` vs the denylist → not retryable ⇒ `metrics.retries("not_retryable")`, return empty.
2. **Per-run budget** — load the failed run's `run_queue` row; `retryCount >= qualityops.scheduling.retry.max-per-run` (default **2**) ⇒ `metrics.retries("budget_exhausted")`, return empty. `retry_count` is monotone along the retry chain, so this one comparison bounds the whole chain.
3. **Per-org budget** — a **live COUNT**, no new table: `SELECT count(*) FROM run_queue WHERE org_id = :orgId AND retry_of IS NOT NULL AND created_at > now() - :window`; `>= qualityops.scheduling.retry.max-active-per-org` (default **20**, `window` default `PT1H`) ⇒ `metrics.retries("budget_exhausted")`, return empty. (A rolling window rather than a running total avoids a counter table and self-heals.)
4. **Enqueue the retry** via a new `RunEnqueueService.enqueueRetry(UUID originalRunId, UUID orgId)`:
   - Read the original `test_runs.config_snapshot` **raw JSON string** (`RunRepository.findConfigSnapshotJson(runId, orgId)`) and the original `run_queue.requested_event_json`.
   - Mint a new `runId`, new `executionId`, new `eventId`, `occurredAt = now()`; **keep the original `correlationId`** (trace-chain continuity).
   - Insert a new `test_runs` PENDING row with the **byte-identical** `config_snapshot` copied verbatim (domain rule #2: the retry runs exactly what the original would have — no re-validation, no re-freeze; the target may since have been deleted and we still want a faithful replay).
   - Build the retry's frozen `RunRequestedEvent` by taking the original event and swapping only `eventId` / `runId` / `executionId` / `occurredAt` (project/suite/env/triggeredBy/testCases unchanged).
   - Insert the `run_queue` QUEUED row with `retry_of = originalRunId`, `retry_count = originalRow.retryCount + 1`, `schedule_id` copied from the original, `priority` copied from the original.
   - Publish nothing — `QueueDispatchJob` picks it up ~a tick later, subject to the same aging priority and per-org concurrency as any run.
   - `metrics.retries("enqueued")`; log `run {retryId} is retry #{n} of {originalRunId} (reason: …)`.

The retry run is a brand-new immutable `test_runs` row linked back only via `run_queue.retry_of`. `RunResponse` gains two **additive nullable** fields `retryOf` (UUID) and `retryCount` (Integer), populated from the `run_queue` row (`QueueSummary` / `QueueRow` / `RunResponse.from(...)` gain the pair; the existing 3-arg and no-arg `from` overloads delegate with nulls). Frontend is out of scope; the additive fields keep `apps/web` compiling.

#### 2.4 Migration V16 — retry columns on `run_queue`

**Decision: columns on `run_queue`, not a sibling `run_retry` table.** `run_queue` is already 1:1 with `test_runs`; the retry linkage and count are per-queue-row facts with no independent lifecycle. A `run_retry(org_id, original_run_id, retry_run_id, attempt, reason, created_at)` table would need its own `org_id`, its own indexes, and a join on every read for zero added expressiveness. The columns option matches ADR-006 §3.1's "1:1 with test_runs" shape.

```sql
-- V16__add_run_queue_retry.sql
ALTER TABLE run_queue ADD COLUMN IF NOT EXISTS retry_of    UUID REFERENCES run_queue (run_id);
ALTER TABLE run_queue ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_run_queue_retry_of
    ON run_queue (retry_of) WHERE retry_of IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_run_queue_retry_window
    ON run_queue (org_id, created_at) WHERE retry_of IS NOT NULL;
```

`retry_of` references `run_queue(run_id)` (already `UNIQUE`) — the original run. Both partial indexes support the per-org window COUNT and chain lookups cheaply. No `org_id` needed on the new columns — the row already carries it.

### 3. `GET /api/v1/admin/queue` — org-scoped queue summary

**Decision: scope to the caller's org. A cross-tenant / platform-wide view is Phase 4.** 2D has no platform-admin role (`OWNER/ADMIN` are org-scoped), so a "top-N orgs by active runs" panel would either leak other tenants' load or require a role that does not exist. Documented as a Phase-4 addition.

New `com.qualityops.api.execution.adapter.in.web.QueueAdminController`:

```
## Endpoint: GET /api/v1/admin/queue

### Purpose
Give an org OWNER/ADMIN a single view of their queue depth, wait, active count,
effective concurrency limit, and the process-wide dispatch/reaper/retry counters.

### Request
(no body; no query params in 2D)

### Response 200
{
  "data": {
    "org": {
      "queuedByPriority": { "HIGH": 0, "NORMAL": 3, "LOW": 1 },
      "oldestQueuedAgeSeconds": 42,
      "activeRuns": 2,
      "effectiveMaxActiveRuns": 5,
      "maxActiveRunsSource": "DEFAULT"        // or "OVERRIDE"
    },
    "process": {                              // NOT org-scoped — labelled as such
      "dispatchThroughput": 18124,
      "dispatchFailed": { "attempts_ceiling": 0, "corrupt_event": 0 },
      "reaped": { "redispatched": 3, "redispatch_exhausted": 0, "stuck_failed": 1,
                  "cancel_reconciled": 0, "reaper_error": 0 },
      "retries": { "enqueued": 12, "budget_exhausted": 1, "not_retryable": 4 }
    }
  }
}

### Authorization
@PreAuthorize("hasAnyRole('OWNER','ADMIN')"). org fields scoped to user.orgId().

### Side effects
None (read-only).
```

The `org` block needs **org-scoped** aggregates the 2C port lacks. New `RunQueueRepository` methods:

```java
Map<RunPriority, Long> queueDepthByPriorityForOrg(UUID orgId);   // WHERE queue_state='QUEUED' AND org_id=?
Optional<Instant>      oldestQueuedEnqueuedAtForOrg(UUID orgId);
long                   activeRunCountForOrg(UUID orgId);          // DISPATCHED+RUNNING, org-scoped
```

The `process` block is read straight off the `MeterRegistry` (`registry.find("qualityops.queue.reaped").counters()` etc.) — no new state. Enveloped with `ApiResponse.success(...)`.

**Grafana dashboard** — committed at `infra/grafana/queue-dashboard.json` (importable; wiring a Grafana service into compose is a devops follow-up, out of ADR scope). Panels, by row:

- **Queue** — `qualityops_queue_depth` by `priority` (timeseries); `qualityops_queue_oldest_age_seconds` (stat, red > 300); `qualityops_queue_active_runs` (stat); `qualityops_queue_wait_seconds` p50/p95/p99 (timeseries from the summary quantiles) + a heatmap; `rate(qualityops_queue_dispatch_throughput[5m])` (timeseries); `qualityops_queue_dispatch_failed` by `reason` (timeseries).
- **Scheduling** — `qualityops_schedule_fires` by `outcome` (timeseries); `qualityops_scheduling_tick_duration` / `qualityops_queue_dispatch_duration` p95 (timeseries); `qualityops_scheduling_leader` by `job` (state-timeline, one lane per job incl. `stuck-run-reaper`, `webhook-dispatch`).
- **2D — Reaper & retry** — `qualityops_queue_reaped` by `kind` (timeseries); `qualityops_scheduling_reaper_duration` p95 (stat); `qualityops_queue_retries` by `outcome` (timeseries).
- **2D — Webhooks** — `qualityops_webhook_delivery` by `outcome` (timeseries); `qualityops_webhook_delivery_duration` p95 (stat).
- **Cancellations** — `qualityops_queue_cancellations` by `phase` (timeseries).

Dashboard-level: `datasource` templating var (`Prometheus`), `instance` multi-select var, 30s refresh, `qualityops` tag.

### 4. `org_run_concurrency` write path

New `com.qualityops.api.execution.adapter.in.web.OrgConcurrencyController`:

```
## Endpoint: PUT /api/v1/admin/orgs/{orgId}/run-concurrency

### Purpose
Let an org OWNER/ADMIN set their own per-org max concurrent active runs.

### Request
{ "maxActiveRuns": 10 }        // @NotNull @Min(1) @Max(1000)

### Response
200 { "data": { "maxActiveRuns": 10, "source": "OVERRIDE" } }
400  VALIDATION_ERROR         // maxActiveRuns missing / <1 / >1000
403  FORBIDDEN                // orgId != caller.orgId, or role below ADMIN

### Authorization
@PreAuthorize("hasAnyRole('OWNER','ADMIN')") PLUS an in-handler check:
    if (!orgId.equals(user.orgId())) throw new AccessDeniedException("cross-org");
Cross-org administration is Phase 4 (there is no platform-admin role in 2D).

### Side effects
Upsert org_run_concurrency (org_id PK). Emits one structured audit log line
(there is no audit table until 2E):
    logger "com.qualityops.api.audit" INFO
    audit action=org.run_concurrency.update actor=<userId> org=<orgId> old=<n|default:5> new=<n>
```

```
## Endpoint: GET /api/v1/admin/orgs/{orgId}/run-concurrency   (recommended sibling)

### Response 200
{ "data": { "maxActiveRuns": 5, "source": "DEFAULT" } }   // override value or the global default

### Authorization
Same as PUT (own org only).
```

New input port `SetRunConcurrencyUseCase` / `GetRunConcurrencyUseCase` implemented by `OrgConcurrencyService`. `OrgConcurrencyRepository` (2C, currently only `findAllOverrides()`) gains:

```java
Optional<Integer> findByOrgId(UUID orgId);
void upsert(UUID orgId, int maxActiveRuns);   // INSERT ... ON CONFLICT (org_id)
                                              //   DO UPDATE SET max_active_runs=EXCLUDED.max_active_runs,
                                              //                 updated_at=now()
```

`source` is `OVERRIDE` when a row exists, else `DEFAULT` (value = `SchedulingProperties.queue().maxActiveRunsPerOrg()`). **No migration** — V15 already created the table (`max_active_runs INT NOT NULL CHECK (max_active_runs > 0)`); the `@Max(1000)` sanity bound is enforced only in the DTO (documented — a future ops need for a higher cap is a one-line DTO change, not a migration).

The dispatcher already reads `org_run_concurrency` (ADR-006 §3.4 step 2); an override takes effect on the next `queue-dispatch` tick with no restart.

### 5. CI execution API — idempotent `POST /api/v1/ci/runs`

New `com.qualityops.api.execution.adapter.in.web.CiRunController`:

```
## Endpoint: POST /api/v1/ci/runs

### Purpose
Enqueue a run from a CI pipeline, safely re-runnable under the same Idempotency-Key.

### Request
Header  Idempotency-Key: <string>     REQUIRED. @Pattern("[A-Za-z0-9_.\\-]{1,200}"); blank ⇒ 400.
Body    { "projectId": "...", "suiteId": "...", "environmentId": "...", "priority": "NORMAL" }
        — exactly CreateRunRequest (reused; priority optional, HIGH gated as on POST /api/v1/runs).

### Response
200  { "data": <RunResponse> }    // FIRST call AND every subsequent same-key+same-body call
400  VALIDATION_ERROR             // missing/blank/oversize Idempotency-Key, or invalid body
403  FORBIDDEN                    // role below MEMBER, or priority=HIGH without OWNER/ADMIN
409  IDEMPOTENCY_KEY_CONFLICT     // same key, DIFFERENT request fingerprint (Stripe-style)

### Authorization
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER') and "
  + "(#request.priority == null or #request.priority != 'HIGH' or hasAnyRole('OWNER','ADMIN'))")
Scoped CI tokens are Phase 4; 2D reuses the caller's JWT (orgId + userId from UserPrincipal).

### Side effects
First call: EnqueueRunUseCase.enqueue(source=MANUAL, triggeredBy=userId) + INSERT ci_idempotency_key.
Repeat call: none — the stored run_id is looked up and its RunResponse returned.
Publishes nothing (the dispatcher publishes runs.requested later, as for every run).
```

**200 for both first and repeat** (per the exit criterion "same run + 200 both times") — not 201. The controller returns `ResponseEntity.ok(ApiResponse.success(...))`; the 409 path is an `IdempotencyKeyConflictException extends ConflictException` (code `IDEMPOTENCY_KEY_CONFLICT`) handled by the existing `GlobalExceptionHandler`.

#### 5.1 Fingerprint

`request_fingerprint` = lowercase-hex SHA-256 of the canonical string `projectId + '|' + suiteId + '|' + environmentId + '|' + (priority == null ? "NORMAL" : priority)` (priority normalised so an explicit `"NORMAL"` and an omitted priority are the same request). Stored `VARCHAR(64)`.

#### 5.2 Concurrency — rely on `UNIQUE (org_id, idempotency_key)`

`CiRunService` is **not** class-level `@Transactional`; it drives one `TransactionTemplate` unit for the create and does plain reads outside (mirrors `QueueDispatchService` / `RunCancellationService`):

1. `find(orgId, key)`:
   - **present, fingerprint matches** → `runRepository.findByIdAndOrgId(row.runId(), orgId)` → `RunResponse` (+ queue summary) → 200.
   - **present, fingerprint differs** → throw `IdempotencyKeyConflictException` → 409.
   - **absent** → step 2.
2. `txTemplate.execute`: `enqueueRunUseCase.enqueue(...)` (its `@Transactional` joins this unit — DB-only, publishes nothing) **then** `ciIdempotencyRepository.insert(orgId, key, fingerprint, runId)`. On commit → 200 with the new run.
3. **Race** — a concurrent first-call committed first, so the `INSERT` hits `UNIQUE (org_id, idempotency_key)` → `DataIntegrityViolationException` → **the whole `TransactionTemplate` unit rolls back, including the losing `test_runs` + `run_queue` insert** (no orphan run, nothing dispatched). `CiRunService` catches the DIVE *outside* the rolled-back tx, re-reads `find(orgId, key)`, and returns the winner's run (200) if the fingerprint matches, else 409.

This is the same "unique constraint is the arbiter, catch-and-re-read" pattern ADR-006 used for `schedule_fire` and `worker.execution_attempt`.

#### 5.3 Migration V17 — `ci_idempotency_key`

```sql
-- V17__create_ci_idempotency_key.sql
CREATE TABLE IF NOT EXISTS ci_idempotency_key (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID         NOT NULL,
    idempotency_key     VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    run_id              UUID         NOT NULL REFERENCES test_runs (id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_ci_idempotency_created_at ON ci_idempotency_key (created_at);
CREATE INDEX IF NOT EXISTS idx_ci_idempotency_run_id     ON ci_idempotency_key (run_id);
```

Carries `org_id` (every table does). **TTL sweep:** `QueueMaintenanceService.prune()` (2C, `@Scheduled PT1H`) gains a third delete — `ci_idempotency_key` rows older than `qualityops.ci.idempotency-retention` (default **`P7D`** — long enough for a CI pipeline re-run days later; Stripe's 24h is tuned for interactive checkout, not CI). The mapping outlives its usefulness once no pipeline will replay that key.

#### 5.4 CI snippets — `docs/api/ci-execution.md`

A new doc with copy-paste blocks (adapters / `curl` only — **no Jenkins plugin**):

- **GitHub Actions** — a step that computes `IDEMPOTENCY_KEY="${{ github.run_id }}-${{ github.run_attempt }}"`, `curl -sS -X POST "$QUALITYOPS_URL/api/v1/ci/runs" -H "Authorization: Bearer $QUALITYOPS_TOKEN" -H "Idempotency-Key: $IDEMPOTENCY_KEY" -H 'Content-Type: application/json' -d '{"projectId":"…","suiteId":"…","environmentId":"…"}'`, then a poll loop on `GET /api/v1/runs/{id}` until `status` is terminal, failing the job on `FAILED`.
- **GitLab CI** — same, keyed `IDEMPOTENCY_KEY="$CI_PIPELINE_ID-$CI_JOB_ID"`, using `CI_JOB_TOKEN`-provided secrets via masked variables.
- **Jenkins** — a `sh` step in a scripted/declarative pipeline keyed `IDEMPOTENCY_KEY="${env.BUILD_TAG}"`, `withCredentials([string(credentialsId: 'qualityops-token', variable: 'QUALITYOPS_TOKEN')])`, same `curl` + poll; explicitly "no plugin — a shell step against the REST API".

Each snippet notes: retry the pipeline step freely — the same key returns the same run; changing the body under the same key is a `409`.

### 6. Caseflow execution contract — a documented contract over existing endpoints, plus signed webhooks

#### 6.1 Endpoint mapping — no new controller

**Decision: Caseflow is the versioned external *description* of endpoints that already exist (plus §5's CI submit), not a new `/api/v1/caseflow/*` surface.** A parallel controller would duplicate auth, validation, and the run model for no capability gain.

| Caseflow operation | Endpoint | Origin |
|---|---|---|
| submit | `POST /api/v1/ci/runs` | 2D §5 |
| status | `GET /api/v1/runs/{id}` | Phase 1 (carries `queueState`/`priority`/`cancelRequested` since 2C; `retryOf`/`retryCount` since 2D §2) |
| cancel | `POST /api/v1/runs/{id}/cancel` | 2C (ADR-006 §5.2) |
| results | `GET /api/v1/runs/{id}/results` | Phase 1 (`ResultController`) |
| artifacts | `GET /api/v1/runs/{id}/artifacts` | 2B3 (ADR-005) |

A per-case results GET already exists (`GET /api/v1/runs/{runId}/results`, paged `TestResultResponse`) — no new results endpoint is needed. `docs/api/caseflow-v1.yaml` is authored as **OpenAPI 3.1**, hand-maintained (not generated), covering exactly those five operations + the completion-webhook payload schema + the signature headers, versioned `info.version: 1.0.0`. A lightweight `CaseflowContractTest` parses the YAML (swagger-parser, transitively present via springdoc; falls back to a SnakeYAML structural assertion) and asserts it is valid and references the five operationIds and the `RunCompletedWebhook` schema — so the doc cannot silently rot.

#### 6.2 Where the webhook endpoint + secret live — a minimal `webhook_endpoint` table

**Decision: an org-scoped `webhook_endpoint` table with management endpoints, not config-only.** A single `qualityops.webhook.url` would be untenantable and unusable in a multi-org lab. The secret is stored **directly** in `webhook_endpoint.secret` (write-only over the API, masked in every response as `"secretSet": true`). **Security tradeoff, stated:** this is plaintext-at-rest in 2D. There is no API-side secret store yet (`EnvFileSecretResolver` is Worker-only; Azure Key Vault is Phase 5). Column-level encryption or a `secretRef`-style indirection for API-held secrets is a **Phase 4 hardening follow-up**, tracked here.

New hexagonal-lite module `com.qualityops.api.webhook` (controller + scheduled job + persistence + outbound HTTP ⇒ multi-adapter ⇒ warrants the layout, per ARCHITECTURE's rule):

```
## Endpoints (webhook/adapter/in/web/WebhookEndpointController)

POST   /api/v1/projects/{projectId}/webhooks      # register  (OWNER/ADMIN)
GET    /api/v1/projects/{projectId}/webhooks      # list (secret masked)  (OWNER/ADMIN)
DELETE /api/v1/webhooks/{id}                       # remove  (OWNER/ADMIN)

Body (register): { "url": "https://…", "secret": "<>=16 chars>", "enabled": true }
Validation: url @URL @Size(max=2048) AND WebhookUrlValidator — https only, host
  must not resolve to loopback/link-local/private/CGNAT/ULA (a lighter inline
  check than the Worker's TargetValidator, which is not on the API classpath;
  documented). secret @NotBlank @Size(min=16,max=255).
Tenancy: project must belong to user.orgId() (404 otherwise); every row org-scoped.
```

`project_id` is **nullable** — a null means "all runs in this org". The management endpoints only create project-scoped rows in 2D; an org-wide row is a manual/seed concern for now (documented).

#### 6.3 Delivery — a `webhook_delivery` outbox table + a ShedLock-locked `@Scheduled` sender

**Decision: a durable `webhook_delivery` table with a scheduled sender, not synchronous inline retries.** ADR-006 accepted a single-purpose "mini-outbox" column; a small dedicated outbox table is the right shape here because deliveries must **survive an API restart**, must **retry with backoff over minutes**, and must be **exactly-once per (run, endpoint)**. Inline retries on a bounded executor lose everything on restart and block the lifecycle consumer.

Flow:

```
## Flow: Signed run-completion webhook

### Trigger
RunLifecycleService.onRunCompleted / onRunFailed, after the guarded test_runs
terminal transition MOVED a row (moved == true).

### Steps
1. RunLifecycleService calls webhook input port
   EnqueueRunWebhooksUseCase.enqueueForTerminalRun(runId, orgId, eventType)
   in the SAME @Transactional unit as the terminal transition.
2. WebhookDeliveryService resolves the run (GetRunUseCase) → projectId, and
   selects enabled webhook_endpoint rows WHERE org_id = ? AND (project_id = ?
   OR project_id IS NULL). For each, INSERT webhook_delivery
   (org_id, webhook_endpoint_id, run_id, event_type, payload_json, state='PENDING',
    attempt=0, next_attempt_at=now()) — payload FROZEN at enqueue for signature
   stability. UNIQUE (run_id, webhook_endpoint_id) makes a redelivered
   runs.completed a no-op INSERT (belt-and-braces on top of the moved gate).
3. WebhookDispatchJob (@Scheduled fixedDelay qualityops.webhook.dispatch-interval
   default PT10S; @SchedulerLock "webhook-dispatch" lockAtMostFor PT5M; gated on
   qualityops.scheduling.jobs-enabled) selects
   webhook_delivery WHERE state='PENDING' AND next_attempt_at <= now()
   ORDER BY next_attempt_at LIMIT qualityops.webhook.batch-size
   FOR UPDATE SKIP LOCKED, and per row (its own tx):
     - WebhookSender POSTs payload_json to endpoint.url with headers:
         X-QualityOps-Event:      run.completed | run.failed
         X-QualityOps-Delivery:   <webhook_delivery.id>
         X-QualityOps-Timestamp:  <epoch seconds>
         X-QualityOps-Signature:  sha256=<hex HMAC-SHA256(endpoint.secret,
                                          "<timestamp>.<body>")>
       connect-timeout PT5S, request-timeout PT10S (JDK HttpClient, no new dep).
     - 2xx → state='DELIVERED';  metrics.webhookDelivery("delivered")
     - non-2xx / IOException / timeout → attempt++, last_error set,
       next_attempt_at = now() + min(initial-backoff * 2^(attempt-1), 1h);
       at attempt >= qualityops.webhook.max-attempts (default 6) →
       state='EXHAUSTED'; metrics.webhookDelivery(attempt>=max ? "exhausted" : "failed")
     - always: metrics.webhookDeliveryDuration record

### Payload (payload_json)
{ "event": "run.completed", "runId": "...", "projectId": "...", "suiteId": "...",
  "environmentId": "...", "status": "PASSED|FAILED", "startedAt": "...",
  "completedAt": "...", "triggeredBy": "...",
  "links": { "self": "/api/v1/runs/<id>", "results": "/api/v1/runs/<id>/results",
             "artifacts": "/api/v1/runs/<id>/artifacts" } }

### Failure handling
- API down between enqueue and send: rows sit PENDING; the next tick sends them.
- Endpoint down: exponential backoff to EXHAUSTED after max-attempts; a metric fires.
- No DLT (HTTP, not Kafka). EXHAUSTED rows are visible for debugging and pruned.

### Idempotency
- Enqueue is gated on the moved boolean AND UNIQUE (run_id, webhook_endpoint_id):
  a redelivered runs.completed inserts nothing.
- Receiver-side: the doc instructs consumers to dedupe on X-QualityOps-Delivery
  and to reject if |now - X-QualityOps-Timestamp| > qualityops.webhook.replay-window
  (default 300s) — the sender only stamps; the window is receiver policy.

### Retention
QueueMaintenanceService also deletes webhook_delivery rows in
(DELIVERED, EXHAUSTED) older than qualityops.webhook.delivery-retention (P7D).
```

`WebhookSignature` is a pure static util (`sign(secret, timestamp, body) -> "sha256=" + hex`) with its own unit test (valid, tampered body, wrong secret, constant-time compare helper).

**Metrics** (no `org` tag): `qualityops.webhook.delivery` counter tag `outcome ∈ {delivered, failed, exhausted}`; `qualityops.webhook.delivery_duration` timer; `qualityops.scheduling.leader{job="webhook-dispatch"}` (wired from the locked job body).

#### 6.4 Migration V18 — `webhook_endpoint` + `webhook_delivery`

```sql
-- V18__create_webhook_endpoint_and_delivery.sql
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID         NOT NULL,
    project_id UUID,                                  -- NULL ⇒ all runs in the org
    url        VARCHAR(2048) NOT NULL,
    secret     VARCHAR(255)  NOT NULL,                -- plaintext at rest in 2D (see ADR §6.2)
    enabled    BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by UUID          NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_lookup
    ON webhook_endpoint (org_id, project_id) WHERE enabled;

CREATE TABLE IF NOT EXISTS webhook_delivery (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID        NOT NULL,
    webhook_endpoint_id UUID        NOT NULL REFERENCES webhook_endpoint (id) ON DELETE CASCADE,
    run_id              UUID        NOT NULL REFERENCES test_runs (id),
    event_type          VARCHAR(32) NOT NULL,
    payload_json        JSONB       NOT NULL,
    state               VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                            CHECK (state IN ('PENDING', 'DELIVERED', 'EXHAUSTED')),
    attempt             INT         NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error          VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, webhook_endpoint_id)
);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due
    ON webhook_delivery (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_org ON webhook_delivery (org_id);
```

`state` is `VARCHAR + CHECK`, not a PG enum — consistent with ADR-006 §3.1's `run_queue` reasoning (states will churn; `ALTER … CHECK` is transaction-safe). Both tables carry `org_id NOT NULL`.

### 7. Migration plan — V16, V17, V18 (append-only, in dependency order)

| File | Purpose | Depends on | `SchemaMigrationIT` assertions to add |
|---|---|---|---|
| `V16__add_run_queue_retry.sql` | `run_queue.retry_of` (FK → `run_queue.run_id`, nullable), `run_queue.retry_count INT NOT NULL DEFAULT 0`, two partial indexes | V13 | `run_queue` has `retry_of` (`is_nullable = YES`) and `retry_count` (`is_nullable = NO`, `column_default` contains `0`); `pg_indexes` for `run_queue` contains `idx_run_queue_retry_of`, `idx_run_queue_retry_window` |
| `V17__create_ci_idempotency_key.sql` | CI dedupe table, `UNIQUE (org_id, idempotency_key)`, `run_id` FK → `test_runs` | V6 (`test_runs`) | table `ci_idempotency_key` exists; unique-constraint columns are exactly `(org_id, idempotency_key)`; `org_id` `is_nullable = NO`; a FK from `run_id` to `test_runs` exists |
| `V18__create_webhook_endpoint_and_delivery.sql` | `webhook_endpoint` + `webhook_delivery` (outbox), `state VARCHAR+CHECK`, `UNIQUE (run_id, webhook_endpoint_id)`, due/partial indexes | V6 (`test_runs`) | both tables exist with `org_id` `is_nullable = NO`; `webhook_delivery.state` `data_type = character varying`; `pg_indexes` contains `idx_webhook_delivery_due`; extend `queueEnums_afterMigration_areNotPgEnumTypes` to also assert `pg_type` has **no** `webhook_delivery_state` |

`flywayHistory_afterMigration_containsVersions1Through15` → **`…Through18`**, `containsExactly("1", … , "18")`. `SchemaMigrationIT`'s class Javadoc updates "V1–V15" → "V1–V18".

No migration for: reaper timestamps (§1.3, derived), `org_run_concurrency` (§4, V15 suffices), retry per-org budget (§2.3, live COUNT).

### 8. New / changed classes and ports

**`apps/api` — execution module**

| Class / port (package `com.qualityops.api.execution…`) | Responsibility |
|---|---|
| `application.scheduler.StuckRunReaper` | `@Scheduled` + `@SchedulerLock("stuck-run-reaper")` wrapper; leader gauge + `reaper_duration` timer; delegates to the service |
| `application.service.StuckRunReaperService` | `sweep()` — reconciliation (a) stranded `DISPATCHED` re-publish, (b) stuck active → `FAILED`; not class-`@Transactional` |
| `application.service.RetryRunService` | `implements RetryRunUseCase`; reason check + per-run + per-org budget; calls `RunEnqueueService.enqueueRetry` |
| `application.port.in.RetryRunUseCase` | `Optional<EnqueueRunResult> retryIfEligible(UUID failedRunId, UUID orgId, String failureReason)` |
| `application.service.RunEnqueueService` **(changed)** | `+ EnqueueRunResult enqueueRetry(UUID originalRunId, UUID orgId)` — copies frozen snapshot + event verbatim, mints new ids, keeps `correlationId`, sets `retry_of`/`retry_count` |
| `application.service.RunLifecycleService` **(changed)** | `onRunFailed`: after `moved`, call `retryRunUseCase.retryIfEligible(...)`; `onRunCompleted`/`onRunFailed`: after `moved`, call `enqueueRunWebhooksUseCase.enqueueForTerminalRun(...)` |
| `application.service.QueueDispatchService` **(changed)** | extract `publishClaimed(candidate, alreadyClaimed)` (claim-publish-`reconcileAfterFailedPublish`) so the reaper reuses the corrupt-event / ceiling / rollback handling |
| `application.port.out.RunQueueRepository` **(changed)** | `+ selectStrandedDispatched`, `+ reclaimStranded`, `+ selectStuckActive`, `+ queueDepthByPriorityForOrg`, `+ oldestQueuedEnqueuedAtForOrg`, `+ activeRunCountForOrg`; `QueueRow`/`QueueSummary`/`DispatchCandidate` unchanged except `QueueRow`+`QueueSummary` `+ retryOf, retryCount` |
| `application.port.out.RunRepository` **(changed)** | `+ int reapToFailed(UUID runId, UUID orgId, Instant ts)`; `+ Optional<String> findConfigSnapshotJson(UUID runId, UUID orgId)` |
| `application.port.out.OrgConcurrencyRepository` **(changed)** | `+ Optional<Integer> findByOrgId(UUID)`, `+ void upsert(UUID, int)` |
| `adapter.out.persistence.RunQueueJpaRepository` / `RunJpaRepository` / `OrgRunConcurrencyJpaRepository` **(changed)** | the native guarded `UPDATE`/`SELECT … FOR UPDATE SKIP LOCKED` / `ON CONFLICT` behind the new port methods |
| `adapter.in.web.CiRunController` | `POST /api/v1/ci/runs` |
| `application.service.CiRunService` | `implements SubmitCiRunUseCase`; fingerprint, find-or-create, `DataIntegrityViolationException` race handling via `TransactionTemplate` |
| `application.port.in.SubmitCiRunUseCase` | `RunResponse submit(String idempotencyKey, CreateRunRequest body, UUID orgId, UUID userId)` |
| `application.port.out.CiIdempotencyRepository` + `adapter.out.persistence.*` | `Optional<CiIdempotencyRow> find(orgId, key)`, `void insert(orgId, key, fingerprint, runId)` |
| `exception.IdempotencyKeyConflictException` | `extends ConflictException`, code `IDEMPOTENCY_KEY_CONFLICT` |
| `adapter.in.web.QueueAdminController` | `GET /api/v1/admin/queue` |
| `adapter.in.web.OrgConcurrencyController` | `PUT`/`GET /api/v1/admin/orgs/{orgId}/run-concurrency` + audit log line |
| `application.service.OrgConcurrencyService` + `application.port.in.{Set,Get}RunConcurrencyUseCase` | upsert / read effective value + source |
| `dto.RunResponse` **(changed)** | `+ UUID retryOf, Integer retryCount` (additive nullable; existing factories delegate with nulls) |
| `dto.QueueAdminSummary`, `dto.SetRunConcurrencyRequest`, `dto.RunConcurrencyResponse`, `dto.CiRunResponse`(=reuse `RunResponse`) | records |

**`apps/api` — new `webhook` module (`com.qualityops.api.webhook…`)**

| Class / port | Responsibility |
|---|---|
| `adapter.in.web.WebhookEndpointController` | register / list / delete endpoints (OWNER/ADMIN, own-project) |
| `application.service.WebhookEndpointService` + `application.port.in.ManageWebhookEndpointsUseCase` | CRUD + `WebhookUrlValidator` (https-only, private-IP denylist) |
| `application.port.in.EnqueueRunWebhooksUseCase` | `void enqueueForTerminalRun(UUID runId, UUID orgId, WebhookEventType type)` — the seam `RunLifecycleService` calls |
| `application.service.WebhookDeliveryService` | resolve run → project, select matching endpoints, insert `webhook_delivery` rows (frozen payload); the per-row send loop |
| `application.scheduler.WebhookDispatchJob` | `@Scheduled` + `@SchedulerLock("webhook-dispatch")`, gated on `jobs-enabled`; leader gauge |
| `application.service.WebhookSender` | JDK `HttpClient` POST with signature headers, bounded timeouts |
| `domain.WebhookSignature` | pure `sign(secret, ts, body)` HMAC-SHA256 util |
| `application.port.out.{WebhookEndpointRepository, WebhookDeliveryRepository}` + `adapter.out.persistence.*` | entities, JPA repos, native due-select `FOR UPDATE SKIP LOCKED` |
| `dto.{RegisterWebhookRequest, WebhookEndpointResponse}` | records; response masks `secret` as `secretSet: true` |

**`apps/api` — config**

| Class | Change |
|---|---|
| `config.SchedulingProperties` | `+ Reaper reaper` (`interval, dispatchGrace, runTimeout, batchSize`), `+ Retry retry` (`enabled, maxPerRun, maxActivePerOrg, window, List<String> nonRetryableReasonPrefixes`) nested records |
| `config.CiProperties` (`@ConfigurationProperties("qualityops.ci")`) | `idempotencyRetention` |
| `config.WebhookProperties` (`@ConfigurationProperties("qualityops.webhook")`) | `enabled, dispatchInterval, batchSize, maxAttempts, connectTimeout, requestTimeout, initialBackoff, deliveryRetention, replayWindow` |
| `config.QueueMetrics` | `+ reaped(String kind)`, `+ Timer reaperDuration()`, `+ webhookDelivery(String outcome)`, `+ Timer webhookDeliveryDuration()`; `leaderHeld` → `Map<String,AtomicLong>` keyed by job (`stuck-run-reaper`, `webhook-dispatch` added) |
| `application.service.QueueMaintenanceService` | `prune()` also deletes old `ci_idempotency_key` and terminal `webhook_delivery` rows |
| `resources/application.yml` | new `qualityops.scheduling.reaper.*`, `qualityops.scheduling.retry.*`, `qualityops.ci.*`, `qualityops.webhook.*` blocks; `spring.task.scheduling.pool.size` `2 → 4` (tick, dispatch, reaper, webhook-dispatch, metrics-refresh, maintenance now coexist) |

### 9. Config keys added (`qualityops.*`) with defaults

```
qualityops.scheduling.reaper.interval            PT60S
qualityops.scheduling.reaper.dispatch-grace      PT2M
qualityops.scheduling.reaper.run-timeout         PT30M
qualityops.scheduling.reaper.batch-size          100
qualityops.scheduling.retry.enabled              true
qualityops.scheduling.retry.max-per-run          2
qualityops.scheduling.retry.max-active-per-org   20
qualityops.scheduling.retry.window               PT1H
qualityops.scheduling.retry.non-retryable-reason-prefixes   ["execution cancelled","run cancelled"]
qualityops.ci.idempotency-retention              P7D
qualityops.webhook.enabled                       true
qualityops.webhook.dispatch-interval             PT10S
qualityops.webhook.batch-size                    50
qualityops.webhook.max-attempts                  6
qualityops.webhook.connect-timeout              PT5S
qualityops.webhook.request-timeout              PT10S
qualityops.webhook.initial-backoff              PT30S
qualityops.webhook.delivery-retention            P7D
qualityops.webhook.replay-window                 PT5M      # receiver guidance; sender only stamps
```

Reused, not re-declared: `qualityops.scheduling.queue.dispatch-max-attempts`, `…queue.send-timeout`, `…queue.max-active-runs-per-org` (the concurrency default / `source: DEFAULT`), `qualityops.scheduling.jobs-enabled` (gates the two new jobs).

### 10. New Micrometer meters (bounded cardinality, no `org` tag)

| Meter | Type | Tags |
|---|---|---|
| `qualityops.queue.reaped` | counter | `kind ∈ {redispatched, redispatch_exhausted, stuck_failed, cancel_reconciled, reaper_error}` |
| `qualityops.scheduling.reaper_duration` | timer | — |
| `qualityops.queue.retries` | counter | `outcome ∈ {enqueued, budget_exhausted, not_retryable}` |
| `qualityops.webhook.delivery` | counter | `outcome ∈ {delivered, failed, exhausted}` |
| `qualityops.webhook.delivery_duration` | timer | — |
| `qualityops.scheduling.leader` | gauge (existing) | `job` gains `stuck-run-reaper`, `webhook-dispatch` |

### 11. Test plan

Naming: `<Class>Test.java` (unit), `<Class>IT.java` (Testcontainers), methods `methodName_condition_expectedResult`. Every IT that touches tenant data asserts isolation.

**Unit**

- `WebhookSignatureTest` — `sign` matches a known vector; tampered body ⇒ different hex; wrong secret ⇒ different hex; `constantTimeEquals` true/false.
- `WebhookUrlValidatorTest` — `http://` rejected; `https://127.0.0.1` / `…10.0.0.5` / `…169.254.169.254` / `…[::1]` rejected; `https://ci.example.com` accepted.
- `CiRunFingerprintTest` — same body ⇒ same fingerprint; `priority=null` vs `"NORMAL"` ⇒ same; different `suiteId` ⇒ different.
- `RetryRunPolicyTest` — reason `"run cancelled before start"` ⇒ not retryable; `"worker interrupted"` ⇒ retryable; `retryCount==maxPerRun` ⇒ budget exhausted; per-org window count arithmetic.
- `StuckRunReaperServiceTest` (mocked ports) — no candidates ⇒ no writes; stranded candidate ⇒ `reclaimStranded` then publish; publish fails at ceiling ⇒ `markDispatchFailed` + `transitionToFailed`; stuck candidate with `reapToFailed` returning 0 ⇒ no queue write (race lost).
- `SchedulingPropertiesBindingTest` — the new nested `reaper`/`retry` records bind from YAML.
- `CaseflowContractTest` — `docs/api/caseflow-v1.yaml` parses as valid OpenAPI 3.1 and declares the five operationIds + `RunCompletedWebhook` schema.

**Integration (`AbstractPostgresIT` / `@EmbeddedKafka`)**

- `SchemaMigrationIT` (extended) — versions `1..18`; the V16/V17/V18 assertions in §7.
- `StuckRunReaperIT`
  - `strandedDispatched_olderThanGrace_republishesRunsRequested` — seed `run_queue` DISPATCHED + `test_runs` PENDING, `dispatched_at` 3 min ago; `reaperService.sweep()`; assert a `runs.requested` on the embedded topic and `dispatch_attempts` incremented; **a foreign-org stranded row is untouched**.
  - `strandedDispatched_atAttemptsCeiling_failsBothTables`.
  - `strandedDispatched_cancelRacedInWindow_reconcilesToCancelled`.
  - `stuckRunning_pastRunTimeout_failsBothTablesNoKafka` — `test_runs` RUNNING, `started_at` 40 min ago; sweep; assert `test_runs.status=FAILED`, `run_queue.queue_state=FAILED`, **no** Kafka record; **foreign-org stuck run untouched**.
  - `stuckRun_realTerminalRaces_reaperIsNoop` — apply `runs.completed` PASSED, then sweep; run stays PASSED.
  - `sweep_runTwice_isIdempotent`.
  - `reapedCounter_incrementsWithKind`.
- `QueueDrivenRetryIT`
  - `runsFailed_transientReason_enqueuesFreshRetryRow` — assert a new `test_runs` PENDING + `run_queue` QUEUED with `retry_of = originalRunId`, `retry_count = 1`, identical `config_snapshot`; then `dispatchAvailable()` publishes it.
  - `runsFailed_redelivered_doesNotEnqueueSecondRetry` (gated on `moved`).
  - `runsFailed_nonRetryableReason_noRetry` (`outcome=not_retryable`).
  - `runsFailed_perRunBudgetExhausted_noRetry` (`retry_count == max-per-run`).
  - `runsFailed_perOrgWindowFull_noRetry`.
  - `runsFailed_foreignOrg_noRetry_noMove` — event `orgId` ≠ run's org ⇒ `moved == false` ⇒ no retry.
  - `runResponse_forRetryRun_carriesRetryOfAndCount`.
- `CiRunControllerIT`
  - `firstCall_returns200_andPersistsMapping`.
  - `sameKeySameBody_returnsSameRun_200`.
  - `sameKeyDifferentBody_returns409_idempotencyConflict`.
  - `missingOrBlankIdempotencyKey_returns400`.
  - `twoConcurrentFirstCalls_sameKey_produceOneRun` — two threads; exactly one `test_runs` row; both responses share the runId.
  - `orgAKey_andOrgBSameKey_areIndependent`; `orgB_cannotGetOrgAsRun_404`.
  - `viewerRole_forbidden_403`; `memberRequestingHighPriority_403`.
- `OrgConcurrencyAdminIT`
  - `owner_setsOwnOrg_200_andDispatcherRespectsIt` — set `maxActiveRuns=2`, flood 3 runs, assert 2 dispatched / 1 held.
  - `get_withOverride_returnsOverrideAndSource`; `get_withoutOverride_returnsDefault`.
  - `set_zeroOrOverMax_returns400`.
  - `ownerOfOrgA_settingOrgB_forbidden_403`; `member_forbidden_403`.
  - `set_emitsAuditLogLine` (via `OutputCaptureExtension` / a log appender — asserts `action=org.run_concurrency.update actor=… org=… old=… new=…`).
- `WebhookEndpointControllerIT` — register/list/delete; `secret` never echoed (`secretSet:true`); `http://` and private-IP URL ⇒ 400; MEMBER ⇒ 403; org B cannot see org A's endpoint.
- `WebhookDeliveryIT` (MockWebServer)
  - `runReachesTerminal_deliversSignedWebhook` — register endpoint; trigger run to `runs.completed`; run `WebhookDispatchJob` (or `webhookDeliveryService.dispatchDue()` directly); assert MockWebServer got a request whose `X-QualityOps-Signature` verifies against the stored secret and `X-QualityOps-Timestamp` is fresh; `webhook_delivery.state=DELIVERED`; `delivery` counter `outcome=delivered`.
  - `endpoint500_retriesWithBackoff_thenExhausted` — MockWebServer always 500; assert `attempt` grows, `next_attempt_at` advances, terminal `state=EXHAUSTED`, counter `outcome=exhausted`.
  - `redeliveredRunsCompleted_noDuplicateDeliveryRow` (moved gate + `UNIQUE (run_id, webhook_endpoint_id)`).
  - `orgBRun_doesNotDeliverToOrgAEndpoint`.
  - `disabledEndpoint_noDeliveryRow`.
  - `pendingRow_survivesRestart_isPickedUpNextTick` — insert a PENDING row directly, run the job, assert delivered.
- `QueueMetricsIT` (extended) — `qualityops.queue.reaped`, `qualityops.queue.retries`, `qualityops.webhook.delivery`, `qualityops.scheduling.reaper_duration`, and `qualityops.scheduling.leader{job="stuck-run-reaper"|"webhook-dispatch"}` are registered.

**Exit-criteria mapping**

| PHASE-2-PLAN §2D / CLAUDE.md exit criterion | Covered by |
|---|---|
| idempotent submit — same key twice ⇒ one run, 200 both | `CiRunControllerIT.firstCall…`, `.sameKeySameBody…` |
| GitHub Actions job polls status, gets a signature-verified completion webhook | `CiRunControllerIT` + `WebhookDeliveryIT.runReachesTerminal…` + `docs/api/ci-execution.md` |
| operator cancels a queued run | 2C `RunCancellationIT` (unchanged) + `StuckRunReaperIT.strandedDispatched_cancelRacedInWindow…` |
| webhook signature unit (valid, tampered, stale) + delivery IT vs MockWebServer | `WebhookSignatureTest` + `WebhookDeliveryIT` |
| queue-metrics IT asserting gauges registered | `QueueMetricsIT` (extended) |
| cancel-before-dispatch / Worker-honours-cancel | 2C ITs (unchanged) |
| `mvn verify` green across all 4 modules; frontend unaffected | additive `RunResponse` fields; no `shared-events` change; no Worker change |

## Consequences

### Positive

- The two ADR-002/006 stranded/stuck gaps are closed by one boring `@Scheduled` job: a crash between the dispatch-claim commit and `send()` is recovered by an idempotent re-publish; a run wedged in `RUNNING`/`PENDING` past a timeout is driven to `FAILED` in both tables with no Worker involvement. Every reaper write is an org-scoped guarded `UPDATE` that no-ops if a real event raced it.
- A run whose whole execution errored (`runs.failed`) is retried automatically as a **fresh immutable run** with a byte-identical frozen snapshot, under a per-run and a per-org budget, with zero new infrastructure — it is just another `QUEUED` `run_queue` row the existing dispatcher handles. A redelivered `runs.failed` cannot double-retry (gated on the `executionId`-guarded `moved` boolean).
- CI systems get a Stripe-style idempotent `POST /api/v1/ci/runs` — pipeline retries are free, body drift under a reused key is a clean `409`, and the unique constraint + catch-and-re-read handles the concurrent-first-call race with no orphan run.
- Org admins can tune their own concurrency cap; the dispatcher honours it on the next tick with no restart. The change is auditable via a structured log line that 2E's `@Audited` can promote to a table.
- The Caseflow contract is one committed YAML over endpoints that already exist plus signed, durable, at-least-once completion webhooks — CI can stop polling. The `webhook_delivery` outbox survives restarts and backs off to a visible `EXHAUSTED` state.
- **Nothing in `packages/shared-events` changes; the Worker is untouched** (no migration, no new topic, no code) — the whole increment is API-side, additive, and reversible: three append-only migrations, one new module, two new `@Scheduled` jobs, additive `RunResponse` fields.
- Every new table (`ci_idempotency_key`, `webhook_endpoint`, `webhook_delivery`) carries `org_id NOT NULL`; every read and write filters by it; ITs assert cross-tenant isolation on each.

### Negative

- `apps/api` gains **two more leader-elected `@Scheduled` jobs** (`stuck-run-reaper`, `webhook-dispatch`) on top of 2C's three, so `spring.task.scheduling.pool.size` rises to 4 and there are five ShedLock lock rows to watch.
- **Three new tables**, a **new `webhook` module** with full hexagonal-lite layout, six new controllers/endpoints, and ~20 new classes.
- `RunLifecycleService.onRunFailed` / `onRunCompleted` do more work on the terminal path: a retry-eligibility check and a webhook fan-out (each an in-transaction DB call, gated on `moved`). A slow webhook-endpoint lookup would slow terminal processing — mitigated by the partial `idx_webhook_endpoint_lookup` index and by doing only the *enqueue* inline (delivery is the async job).
- The webhook secret is **plaintext at rest** in 2D (`webhook_endpoint.secret`). Acceptable for a lab; a real deployment needs column encryption or a Key Vault-backed `secretRef` — explicitly deferred to Phase 4.
- `GET /api/v1/admin/queue` is org-scoped only; a platform operator still needs Grafana for the cross-tenant picture until Phase 4 adds a platform-admin role.
- `RunResponse` widens by two nullable fields (`retryOf`, `retryCount`); the frontend types need an additive update (out of scope this increment, but the contract stays compatible).

### Risks

- **Reaper vs. a slow-but-alive run.** `reaper.run-timeout` (30 min) must exceed the longest legitimate run (browser suites can be long). Too low ⇒ a healthy long run is killed `FAILED`; the Worker's terminal then no-ops against the already-`FAILED` row and the result is lost. Mitigation: 30 min default is generous for 2B suites; the value is configurable per deployment; `qualityops.queue.reaped{kind="stuck_failed"}` surfaces how often it fires.
- **Re-publish amplification.** If the broker is down, the reaper re-publishes stranded rows every `reaper.interval` until `dispatch-max-attempts`, then fails them. `reclaimStranded` advances `dispatched_at` each pass so a row is not re-picked within a grace window, bounding the rate; the attempts ceiling bounds the total. A prolonged broker outage ends with stranded runs `FAILED` rather than stuck — acceptable and visible.
- **Retry storms.** A systematically broken environment makes every run `runs.failed` and every failure spawns a retry. The per-run budget (2) and the per-org rolling-window budget (20/hour) cap the amplification; `qualityops.queue.retries{outcome="budget_exhausted"}` is the alert signal. A retried run that also fails does **not** chain past `max-per-run`.
- **CI idempotency-key reuse across genuinely different intents.** A pipeline that reuses `github.run_id` without `run_attempt` and changes the suite between attempts gets a `409` — correct, but surprising. The `ci-execution.md` snippets all key on `run_id + run_attempt` (or `BUILD_TAG`) to avoid it.
- **Webhook SSRF.** Endpoint URLs are org-admin-supplied and POSTed to by the API. `WebhookUrlValidator` (https-only + private-IP denylist) is lighter than the Worker's `TargetValidator` (no per-request DNS re-resolution, no redirect handling — the sender disables redirects). A determined admin could still register a URL that DNS-rebinds after validation. Documented; IP-pinned delivery is a hardening follow-up, consistent with ADR-003 §5's accepted residual.
- **Rolling-deploy skew.** Old API + new API replica: both run the reaper / webhook job; ShedLock serialises, and the old replica simply lacks the new jobs (nothing fires twice). New API + old API replica during rollout: the old replica does not reap or deliver webhooks, so recovery/notification lags until it is replaced — no corruption. `RunResponse`'s new fields are ignored by an old deserialiser (`FAIL_ON_UNKNOWN_PROPERTIES=false`). Deploy the API fleet together, as ADR-002…006 already require.
- **`webhook_delivery` growth.** Bounded by the `QueueMaintenanceService` prune (`delivery-retention` 7d) and by the `UNIQUE (run_id, webhook_endpoint_id)` guard (one row per run per endpoint).

## Alternatives considered

### Stuck-run reaper

- **Re-use `claimForDispatch` semantics for the stranded row.** Rejected: `claimForDispatch` is `WHERE queue_state='QUEUED'`; a stranded row is `DISPATCHED`. Overloading it with a second predicate pollutes the dispatcher hot path. A dedicated `reclaimStranded` keyed on `queue_state='DISPATCHED' AND dispatched_at < :cutoff` keeps the two claim predicates independent.
- **Publish-then-mark for the re-dispatch.** Rejected for the same reason ADR-006 §3.4 chose claim-then-publish: a concurrent cancel in the window must see `DISPATCHED` and take the cooperative path, not race a `CANCELLED` against a Worker that already has the event.
- **Add `test_runs.updated_at` (migration) to measure staleness.** Rejected: adds a mutable column to the immutable run aggregate (domain rule #2) for something `started_at` + `run_queue.dispatched_at` already express.
- **A `runs.reap` Kafka command to the Worker to force-kill.** Rejected: the Worker has no `run_queue` access and a genuinely-wedged Worker will not react; the reaper's job is to make the *authoritative* state consistent, which is purely an API-side `UPDATE`. Forceful kill remains a non-goal.
- **One combined query for stranded + stuck.** Rejected: they need different time windows (`dispatch-grace` for a cheap idempotent re-publish, `run-timeout` for a terminal give-up) and different actions; two focused selects are clearer and independently tunable.

### Queue-driven retry

- **A sibling `run_retry` table.** Rejected: the linkage and count are per-`run_queue`-row facts with no independent lifecycle; a table adds its own `org_id`, indexes, and a join per read for no gain. Columns on `run_queue` match the "1:1 with `test_runs`" shape (ADR-006 §3.1).
- **A reason allowlist for "retryable".** Rejected: it must be kept in lockstep with every Worker reason string. `runs.failed` already *means* "not a test verdict"; a short denylist of clearly-permanent prefixes (cancellations) is lower-maintenance and fails safe.
- **Retry `runs.completed` with aggregate `FAILED` too (configurable).** Rejected, same as ADR-005 §3.1: that is a real (possibly flaky) test result; retrying it masks regressions and inflates pass rates. Flaky handling is Phase 3.
- **A new `runs.retry` topic / re-publish from a scheduler.** Rejected: a retry is just a new `QUEUED` `run_queue` row; the existing dispatcher publishes it with the same priority aging and per-org fairness. No new topic, no new event — matching the "prefer NO new events for 2D" constraint.
- **Enqueue the retry in a separate transaction after the lifecycle handler commits.** Rejected: a crash between the two would drop the retry silently. Joining the handler's transaction makes the terminal + retry atomic and the `moved` boolean the single dedup point.

### CI execution API

- **Ignore the body on a key hit (return the original regardless).** Rejected: a CI author who changes the suite under a reused key would silently get the old run with no signal. Stripe-style `409 IDEMPOTENCY_KEY_CONFLICT` on a fingerprint mismatch is explicit.
- **`201 Created` on first call, `200` on repeat.** Rejected: the exit criterion is "same run + 200 both times"; a stable status code is friendlier to CI scripts that branch on it.
- **A generic `common/IdempotencyKeyStore` for all endpoints (PHASE-2-PLAN wording).** Rejected as premature: only `POST /api/v1/ci/runs` needs it in 2D; a single-purpose `ci_idempotency_key` table with a `run_id` FK is simpler and self-documenting. A generic store can be extracted later if a second endpoint needs one.
- **Serialise concurrent first-calls with an advisory lock.** Rejected: the `UNIQUE (org_id, idempotency_key)` constraint plus catch-`DataIntegrityViolationException`-and-re-read is the same pattern already used for `schedule_fire` and `worker.execution_attempt`; a lock adds contention for no benefit.
- **A TTL of 24h (Stripe default).** Rejected for CI: a pipeline can legitimately be re-run days later; 7d is a safer lab default and the value is configurable.

### Caseflow contract & webhooks

- **New `/api/v1/caseflow/*` controllers.** Rejected: they would duplicate auth, validation, and the run model. Caseflow is the *documented contract* over `POST /api/v1/ci/runs` + `GET /api/v1/runs/{id}` + `/cancel` + `/results` + `/artifacts` — a YAML plus the webhook, no new controller.
- **Synchronous inline webhook delivery with N retries on a bounded executor.** Rejected: loses everything on an API restart and blocks the lifecycle consumer while retrying a dead endpoint. A `webhook_delivery` outbox table + a ShedLock-locked `@Scheduled` sender survives restarts and backs off out-of-band. ADR-006 kept a mini-outbox *column*; a small dedicated outbox *table* is justified here by the durability and exactly-once-per-(run,endpoint) requirement.
- **A full transactional outbox for all Kafka + HTTP side effects.** Rejected as scope creep, consistent with ADR-006 — the `webhook_delivery` table covers exactly the one HTTP fan-out 2D needs; a general outbox stays a Phase-7 exercise.
- **Config-only webhook URL/secret (`qualityops.webhook.url`).** Rejected: untenantable; a multi-org platform needs per-org endpoints. A `webhook_endpoint` table with management endpoints makes it real.
- **Store the webhook secret via a `secretRef` indirection like 2B3.** Rejected for 2D: `secretRef` resolution is Worker-side (`EnvFileSecretResolver`); the webhook sender is API-side and there is no API secret store until Phase 5. The secret is stored directly, masked in responses, with the plaintext-at-rest tradeoff called out and a Phase-4 hardening tracked.
- **A `runs.completed.webhook` Kafka event consumed by the webhook module.** Rejected: the webhook module is in the same app; a direct in-process port call (`EnqueueRunWebhooksUseCase`) inside the already-transactional lifecycle handler is simpler and keeps the enqueue atomic with the terminal transition. Kafka would re-introduce the delivery/ordering problem the outbox already solves.

### Queue summary endpoint

- **Platform-wide (all orgs) summary now.** Rejected: 2D has no platform-admin role; a cross-tenant view would leak other orgs' load. Scoped to the caller's org; cross-tenant is Phase 4.
- **Per-org tags on the queue gauges to power a "top-N orgs" panel.** Rejected, same as ADR-006 §6: unbounded cardinality. The endpoint returns the caller's own org aggregates via dedicated `…ForOrg` queries; process-wide counters are labelled `process`.

## Documentation updates when 2D lands

- **`CLAUDE.md`** — add a "Phase 2D … is COMPLETE — see `docs/architecture/decisions/007-…md`" bullet under **CURRENT PHASE** summarising the reaper, queue-driven retry, `org_run_concurrency` write path, `GET /api/v1/admin/queue`, `POST /api/v1/ci/runs`, and Caseflow/webhooks; change "Next increment is **Phase 2D**. Do NOT start it until told." → "Next increment is **Phase 2E**." Update the **Stack** table: API row gains "stuck-run reaper + queue-driven retry + idempotent CI API + signed completion webhooks (2D, ADR-007)"; add a `webhook` module mention. Update the project-layout tree to add `apps/api/.../webhook/` and `docs/api/{caseflow-v1.yaml,ci-execution.md}` and `infra/grafana/queue-dashboard.json`.
- **`ARCHITECTURE.md`** — new "### Phase 2D — reaper, retry, CI API, Caseflow (ADR-007)" subsection under *Key design decisions*; add `webhook/` to the module list (`com.qualityops.api` tree); *Data model* → add V16 (`run_queue.retry_of`/`retry_count`), V17 (`ci_idempotency_key`), V18 (`webhook_endpoint`, `webhook_delivery`) with the "VARCHAR + CHECK, not PG enum" note on `webhook_delivery.state`; *API design → Endpoints* → add `POST /api/v1/ci/runs`, `GET /api/v1/admin/queue`, `PUT|GET /api/v1/admin/orgs/{orgId}/run-concurrency`, `POST|GET /api/v1/projects/{projectId}/webhooks`, `DELETE /api/v1/webhooks/{id}`; *Execution flow* → add the reaper and the retry/webhook hooks on the terminal path; *Technology decisions log* → rows for "Stuck-run recovery = ShedLock `@Scheduled` reaper with guarded UPDATEs", "CI idempotency = `(org_id, idempotency_key)` unique table + fingerprint", "Webhook delivery = `webhook_delivery` outbox table + scheduled sender"; *Dependencies* → note "no new dependency (JDK `HttpClient` for webhook delivery; swagger-parser test-only via springdoc)".
- **`docs/product/PHASE-2-PLAN.md` §2D** — mark **✅ COMPLETE** with a pointer to ADR-007; note the plan text is superseded where it differs: the idempotency table is **V17** (not `V11`); `RunCancellationService` and queued-run **list + cancel** already landed in **2C** (ADR-006 §5), so 2D's cancel work is only the reaper's stranded/stuck reconciliation; `common/IdempotencyKeyStore.java` is instead a dedicated `ci_idempotency_key` table + repository.
- **`docs/product/ROADMAP.md`** — tick the Phase 2 line items for queue observability, CI execution API, and signed webhooks if enumerated.
- **New files** — `docs/architecture/decisions/007-queue-reaper-retry-ci-caseflow.md` (this ADR), `docs/api/caseflow-v1.yaml`, `docs/api/ci-execution.md`, `infra/grafana/queue-dashboard.json`.

## Implementation notes & deviations (2026-09-03)

**Status — 2D COMPLETE.** architect → planner → implementer → reviewer cycle done;
all reviewer must-fix/should-fix findings landed. Verified green: `mvn -B -ntp verify`
across `packages/shared-events`, `apps/api`, `apps/worker`, `apps/gateway`
(Testcontainers ITs incl. `StuckRunReaperIT`, `QueueDrivenRetryIT`, `CiRunControllerIT`,
`OrgConcurrencyAdminIT`, `WebhookEndpointControllerIT`, `WebhookDeliveryIT`,
`CaseflowContractTest`, `SchemaMigrationIT` 1..18, `QueueMetricsIT`; the 2C dispatch/
cancel/orchestration ITs unchanged and still green); `mvn -B -ntp -DskipITs verify`;
`apps/web` lint + typecheck + vitest (77) + build; a full `docker compose up` stack
(Flyway V1–V18 on a fresh Postgres, all services healthy) + the Playwright
login→project→env→suite→case→run→results smoke.

Deviations from the design text above, resolved during implementation/review:

1. **Reaper — `RunLifecycleService.onRunFailed` calls `retryIfEligible(...)` BEFORE
   the queue-terminal `transitionQueueState(...FAILED, true)`**, not after as the
   §2.2 sample shows. That write nulls `run_queue.requested_event_json`, which
   `enqueueRetry` reads verbatim (with `correlationId` + `retry_count`); the sample
   ordering would have thrown on every transient failure. Still gated on `moved`,
   still one `@Transactional` unit — the `moved` boolean remains the sole dedup point.
   The `RUN_FAILED` completion webhook is **suppressed when a retry was enqueued**
   (a Caseflow consumer must not fail a pipeline on an attempt that is about to
   re-run); the retry run delivers its own terminal webhook.

2. **Reaper stranded-DISPATCHED selection does NOT exclude `cancel_requested` rows**
   (§1.2's literal SQL has `AND rq.cancel_requested = FALSE`). A stranded
   (never-published) row can never be reached by the cooperative `runs.cancel` path,
   so the reaper is the only thing that can resolve it — and when a cancel is
   pending, `CANCELLED` is the right terminal. The service picks the branch with
   two atomic, grace-guarded UPDATEs: `reclaimStranded` (`cancel_requested = FALSE`
   → re-publish) and a new `reclaimStrandedCancel` (`cancel_requested = TRUE`
   → `CANCELLED`, nulls the frozen event). Neither matches a row a legitimate
   concurrent dispatch just re-claimed (fresh `dispatched_at` fails the grace
   guard), so the reviewer's "yanks a legit re-dispatch back to QUEUED" foot-gun
   cannot occur — the reaper leaves such a row alone.

3. **`RunEnqueueService.enqueueRetry` builds the retry `run_queue` row from the id
   the repository actually assigned** (`savedRetry.id()`), not a pre-generated one —
   `RunEntity` has a `@GeneratedValue` id, so `save()` may mint a fresh UUID. This
   mirrors `RunEnqueueService.enqueue`, which already builds its queue row from
   `saved.id()`.

4. **`webhook_delivery` outcome writes are conditional** (`... WHERE id = :id AND
   state = 'PENDING'`, and `markRetry` additionally `AND attempt = :attempt - 1`)
   so a stale duplicate send after a ShedLock lease expiry cannot resurrect a
   `DELIVERED` row. `qualityops.webhook.batch-size` is **20** (not 50) so
   `batch × request-timeout` (200 s) stays under the `webhook-dispatch`
   `lockAtMostFor` (PT5M). `markExhausted` records the final `attempt` count.
   `qualityops.webhook.enabled=false` now genuinely disables both enqueue and
   dispatch. `WebhookDeliveryService.dispatchDue` isolates each row in its own
   try/catch so one poison row does not abandon the batch.

5. **`WebhookEndpointService` is not class-`@Transactional`** — `register` does a
   blocking DNS resolution (`WebhookUrlValidator`) that must not run inside an open
   transaction; each operation is a single repository call (each adapter method is
   its own tx). `WebhookUrlValidator` also rejects `0.0.0.0/8` and the IPv4
   limited-broadcast address.

6. **`QueueMetrics` pre-registers** the `qualityops.queue.reaped{kind}`,
   `qualityops.queue.retries{outcome}`, `qualityops.webhook.delivery{outcome}` and
   `qualityops.queue.dispatch_failed{reason}` counters for every known tag value in
   its constructor, so a scrape (and a `rate()`) sees them at 0 before the first
   event rather than absent.

7. **`CaseflowContractTest`** parses `docs/api/caseflow-v1.yaml` with SnakeYAML
   (swagger-parser is not on the classpath) and pins `openapi 3.1.x`,
   `info.version 1.0.0`, the five path operationIds, the `RunCompletedWebhook`
   schema, `bearerAuth`, and the four `X-QualityOps-*` signature headers.
