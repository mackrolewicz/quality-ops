# ADR-002: Extract the execution Worker into its own database-free service

## Status
Accepted

Refines docs/product/PHASE-2-PLAN.md § 2A. Where the plan text said "move the
Kafka consumers as-is" and "the Worker shares the same PostgreSQL", this ADR
supersedes it: the Worker is stateless and never touches the database.

> **Amended in part by ADR-003 (Phase 2B1):** §4 ("no datasource, no JPA, no
> Flyway, no PostgreSQL driver") is narrowed — the Worker gains a datasource
> scoped to a dedicated `worker` schema (one table, its own Flyway stream), used
> only as a durable execution-attempt / side-effect ledger. §1 ("the API is the
> sole writer of authoritative run/result state") is preserved.

## Context

Phase 1 shipped run orchestration as an event-driven flow whose consumers ran
in-process inside `apps/api`:

- `execution/RunRequestedConsumer` listened on `runs.requested`, flipped the
  run `PENDING -> RUNNING` via a conditional UPDATE, slept 200-500 ms to
  simulate work, resolved `PASSED`/`FAILED` at random, and published
  `runs.completed`.
- `result/RunCompletedConsumer` listened on `runs.completed` and wrote one
  `test_results` row per snapshot case, guarded by `existsByRunId` and the
  `uq_test_results_run_case` unique constraint.

ARCHITECTURE.md decision #1 always intended a Worker split; decision #9 requires
the API never to call the Worker directly. Phase 2A performs the split.

Two questions had to be answered:

1. Does the Worker keep a datasource and write run/result state directly (as the
   Phase-1 consumers did), or is it stateless with the API as the sole writer?
2. How do `apps/api` and `apps/worker` share the Kafka event types without
   drift?

Constraints: multi-tenancy on every event and row; idempotency under Kafka
at-least-once delivery; runs are immutable (their configuration is snapshotted
at trigger time); boring, reversible technology; modules communicate through
services, not shared internals; independent build/test/deploy.

## Decision

### 1. The Worker is stateless; the API owns all database writes

`apps/worker` (new Spring Boot app, package `com.qualityops.worker`) has **no
datasource, no JPA, no Flyway, no PostgreSQL driver**. It consumes
`runs.requested`, performs the existing *simulated* execution, and publishes
lifecycle events. It never queries QualityOps tables.

The API publishes a **versioned, immutable, self-contained** `RunRequestedEvent`
that carries the full frozen test-case snapshot, so the Worker has everything it
needs without a database.

The API gains lifecycle back-consumers (group `api-execution`) for
`runs.started` / `runs.completed` / `runs.failed` that apply status transitions,
and keeps the result-generating consumer (group `api-results`) on
`runs.completed`. Every write is a conditional `UPDATE` (or an insert guarded by
a unique constraint), preserving the Phase-1 idempotency mechanisms.

### 2. Event flow and topics

    runs.requested   API    -> Worker         key = runId
    runs.started     Worker -> API            key = runId
    runs.completed   Worker -> API (x2 grps)  key = runId
    runs.failed      Worker -> API            key = runId

- `RunCompletedEvent` carries a terminal TEST outcome (`PASSED` | `FAILED`) plus
  the frozen snapshot. API: `{PENDING,RUNNING} -> PASSED|FAILED`, then generate
  one `test_results` row per snapshot case.
- `RunFailedEvent` means the execution itself errored (interrupt / infra /
  harness). API: `{PENDING,RUNNING} -> FAILED`, generate no results.
- `run_status` reuses `FAILED` for both cases. No `ERROR` label. **No database
  migration in Phase 2A.**

Every event carries an envelope: `eventId`, `correlationId`, `orgId`, `runId`,
`executionId`, `occurredAt`, `schemaVersion`. `correlationId` is minted by the
API at trigger and copied verbatim onto every downstream event. `runId` (the
API `test_runs` PK) is the Kafka key on every topic and the authoritative run
identity. `executionId` is the execution *attempt* id, minted by the API and
echoed by the Worker; 1:1 with `runId` in Phase 2A, distinct so that retries /
re-dispatch in Phase 2B/2D do not change the wire format. `schemaVersion` starts
at 1 and is bumped only on a breaking payload change.

### 3. Shared event types: a dedicated library module

New Maven module `packages/shared-events`, artifactId `qualityops-shared-events`,
`packaging=jar`, package `com.qualityops.events`, **zero compile dependencies**.
It holds: `RunEvent` (sealed envelope interface), `RunRequestedEvent`,
`RunStartedEvent`, `RunCompletedEvent`, `RunFailedEvent`, `TestCaseSnapshotItem`,
and `RunOutcome {PASSED, FAILED}`. Both `apps/api` and `apps/worker` depend on
it. `spring.json.trusted.packages` is already `com.qualityops.*`, so no Kafka
config change is required.

The API domain keeps its own `RunStatus` (the full persistent state machine) and
its own `TestCaseSnapshotItem` (what the `config_snapshot` jsonb serializes).
Mapping between the domain types and the shared wire types happens in two small
adapter-edge mappers. This keeps the immutable persisted snapshot decoupled from
the Kafka contract: either can evolve without forcing a change on the other, and
the hexagonal rule ("no infrastructure types in the domain") is respected -- a
wire contract is an adapter concern.

### 4. Worker health without a database

The Worker adds only `spring-boot-starter-web` (MVC) and
`spring-boot-starter-actuator` -- no security, no data starters -- to expose
`GET /actuator/health` on port 8081 for the Docker/compose healthcheck. Its
consumer group is `worker-execution`, distinct from the API's groups. It keeps
its own `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (3 x 1 s ->
`<topic>.DLT`). The full retry/DLT policy is deferred to Phase 2D.

### 5. Build, deploy, docs

`apps/worker` and `packages/shared-events` join the root reactor. Reactor order:
shared-events, then api and worker (independent of each other), then gateway.
`Dockerfile.api` build stage copies all four child POMs and builds with
`-pl apps/api -am`; new `Dockerfile.worker` mirrors it with `-pl apps/worker
-am`. A `worker` service is added to `docker-compose.dev.yml` (depends on Kafka
only). ARCHITECTURE.md decisions #1, #2, #9, the execution-flow section, and the
Dependencies table are updated.

## Consequences

### Positive
- The Worker can be scaled, restarted, and deployed with zero database
  coupling; a Worker bug cannot corrupt run state.
- One authoritative wire schema in one module, pinned by a JSON contract test --
  a field rename breaks the build, not production.
- Reordering tolerance: `runs.completed` / `runs.failed` transition from PENDING
  or RUNNING, so they survive arriving before `runs.started`.
- Idempotency is unchanged and centralised in the API (conditional UPDATE,
  `existsByRunId`, `uq_test_results_run_case`).
- Independent build/test/deploy for api, worker, gateway, shared-events.
- Realises ARCHITECTURE.md decision #1 without violating decision #9.

### Negative
- A new module and a new deployable to build, run, and monitor.
- Two adapter-edge mappers (domain snapshot <-> wire snapshot; `RunOutcome` <->
  `RunStatus`).
- Larger events on the wire: the frozen snapshot travels on `runs.requested` and
  again on `runs.completed`.
- The API now has one lifecycle back-consumer with three @KafkaListener methods
  where Phase 1 had one.

### Risks
- **Publish-after-commit gap:** the API writes `test_runs` PENDING then
  fire-and-forget publishes `runs.requested`; a Kafka outage between the two
  loses the trigger and strands the run at PENDING. Accepted for 2A;
  transactional outbox is a later exercise.
- **Stuck RUNNING:** if the Worker crashes after `runs.started` and before a
  terminal event, the run stays RUNNING with no reaper. Addressed by 2D
  queued-run controls / timeouts.
- **Deploy skew:** during a rolling deploy, an old API image emits type headers
  `com.qualityops.api.execution.event.*` that the new Worker cannot map ->
  records go to the DLT. Mitigation: deploy api + worker together; in dev,
  `docker compose down -v` resets the ephemeral topics. Reversible by reverting
  both artifacts.
- **Worker cannot self-dedupe across restarts:** it uses a bounded in-memory
  `runId` guard only. Correctness relies on the API's conditional writes, which
  is the intended backstop.

## Alternatives considered

### Shared events: Option A -- dedicated `qualityops-shared-events` jar (chosen)
- Pros: single source of truth; one place for the contract test; no Kafka
  `type.mapping` config; type headers already trusted via `com.qualityops.*`.
- Cons: a new module; the published artifact coordinates and package name
  (`com.qualityops.events`) are mildly sticky once other tooling depends on them.

### Shared events: Option B -- duplicate records in each app + `spring.json.type.mapping`
- Pros: no new module; each app fully owns its copy; copies can diverge
  temporarily.
- Cons: two sources of truth for a wire contract -> silent drift when a field is
  added on one side only; the contract test has no shared home; requires
  `spring.json.type.mapping` on both apps (or disabling type headers and mapping
  by topic) -- extra, fragile configuration. Rejected.

### Reuse `packages/shared-types` for the events
- The roadmap earmarks `shared-types` for FE/BE OpenAPI/TypeScript DTO sync -- a
  different concern with a different toolchain. A sibling `shared-events` module
  keeps the two purposes separate. `shared-types` is left as-is.

### Worker with its own datasource (writes run/result state directly -- the
### Phase-1 model, and what PHASE-2-PLAN § 2A literally said)
- Pros: smallest diff -- move the `@KafkaListener` classes and their JPA
  adapters verbatim; no new events; no envelope work.
- Cons: two services writing the same tables -> schema and migration coupling
  (the Worker would need `ddl-auto=validate` against a schema it does not own);
  a Worker bug can corrupt authoritative state; violates the brief's "all DB
  writes stay in the API"; makes the Worker harder to scale and reason about;
  keeps a hidden shared-database integration point that the modular-monolith
  principle wants to avoid. Rejected in favour of a stateless Worker.

### Envelope as composition (`EventEnvelope` + generic `Message<T>`)
- Pros: DRY -- envelope declared once.
- Cons: generic-wrapper erasure fights spring-kafka's type-header
  deserialization and `trusted.packages`; payload evolution is awkward.
  Rejected in favour of a sealed interface with per-record envelope fields.

### Single combined `runs.lifecycle` topic instead of started/completed/failed
- Pros: one partition stream -> total per-run ordering for free.
- Cons: consumers must branch on an event-type discriminator; harder to scale
  or DLT per lifecycle phase; departs from the repo's `<domain>.<action>` topic
  convention and the existing `runs.requested` / `runs.completed` names.
  Rejected; cross-topic reordering is instead handled by making the terminal
  transition accept PENDING or RUNNING.
