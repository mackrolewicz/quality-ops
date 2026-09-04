# ADR-003: Real API-test execution in the Worker, with a durable execution-attempt ledger

## Status
Accepted

Amends ADR-002 §4 ("Worker health without a database" / "no datasource, no JPA,
no Flyway, no PostgreSQL driver"). Preserves ADR-002 §1 ("the API owns all
database writes" / "the API is the sole writer of authoritative run/result
state"). Realises PHASE-2-PLAN.md § 2B, increment 2B1.

## Context

Phase 2A (ADR-002) extracted a stateless Worker that *simulates* execution:
`RunRequestedConsumer` → `ExecutionSimulationService` (sleep 200-500 ms on a
virtual thread, 80/20 PASSED/FAILED) → `runs.started` then `runs.completed` /
`runs.failed`. Duplicate delivery is absorbed by (a) a bounded in-memory
`ProcessedRunTracker`, lost on restart by design, and (b) the API's org-scoped
conditional `UPDATE`s, which are the real backstop.

2B1 replaces the sleep with a real outbound HTTP call against an immutable
API-test snapshot (method, URL, headers, body, expected status, per-request
timeout, response-size cap, basic assertions). Three properties that were
acceptable for a sleep are not acceptable for a real side effect:

1. **Duplicate execution must not fire the HTTP call twice.** `ProcessedRunTracker`
   is in-memory; a Worker restart between "call sent" and "offset committed"
   re-delivers `runs.requested` and re-executes. The API's conditional writes
   converge run *state*, but they cannot un-send an HTTP request.
2. **Execution-attempt identity must be enforced end to end.** `executionId` is
   minted by the API and echoed by the Worker but never persisted;
   `RunLifecycleService` carries a `// TODO(2B): also guard on executionId`. A
   stale or foreign `executionId` currently still drives a transition.
3. **Outbound calls must be SSRF-safe and their inputs/outputs must be
   redacted.** A QA platform invites users to point it at arbitrary URLs.

Constraints carried from ADR-001/002: multi-tenancy on every event and row;
idempotency under Kafka at-least-once; runs are immutable (config snapshotted at
trigger); boring, reversible technology; the API is the sole writer of
authoritative state; the API never calls the Worker (no synchronous
orchestration); Flyway for all schema changes.

## Decision

### 1. `ExecutionRunner` output port with two adapters

`com.qualityops.worker.execution.application.port.out.ExecutionRunner` executes
**one** snapshot case and returns a structured `CaseExecutionResult` (status ∈
PASSED | FAILED | TIMEOUT | BLOCKED | ERROR). Adapters:
`SimulatedExecutionRunner` (retained, `kind() == SIMULATED`) and
`ApiExecutionRunner` (new, `kind() == API`, JDK `java.net.http.HttpClient`).

`ExecutionSimulationService` is split: a new orchestrator `RunExecutionService`
(implements the unchanged `ProcessRunRequestedUseCase`) owns claim, `runs.started`,
per-case iteration, aggregation, the terminal ledger write and the terminal
event; `SimulatedExecutionRunner` owns the per-case sleep and 80/20 draw.

Runner selection: a global `qualityops.worker.execution.mode`
(`simulated | real | auto`, default `auto`) combined with a per-case rule.
`ExecutionRunnerResolver.resolve(caseItem)`: `SIMULATED` ⇒ always simulated;
`REAL` ⇒ always the API runner (a case without an `apiRequest` snapshot ⇒
`BLOCKED`); `AUTO` ⇒ API runner iff `caseItem.apiRequest() != null`, else
simulated. A v1 `RunRequestedEvent` (no `apiRequest` on any item) therefore runs
exactly as it does today.

### 2. Persistent execution-attempt identity

`test_runs` gains a `NOT NULL UNIQUE` `execution_id` column (migration
`V8__add_test_runs_execution_id.sql`; existing rows backfilled with
`gen_random_uuid()`). `RunService.trigger` mints one `executionId`, persists it,
and puts the same value on `RunRequestedEvent`. `RunRepository.transitionStatus`
and `transitionToTerminal` gain an `executionId` parameter; the three JPA
`UPDATE`s add `AND r.executionId = :executionId`. A stale or foreign
`executionId` ⇒ 0 rows ⇒ the existing logged no-op. `ResultService.generateResults`
additionally skips when `event.executionId()` ≠ the persisted `run.executionId()`.

A single 1:1 column (not a `run_executions` child table) is deliberate: retries /
re-dispatch are Phase 2C/2D, which will design the attempt+queue model with the
columns it actually needs. The column is additive and reversible.

### 3. Durable duplicate-execution prevention: a Worker-owned attempt ledger

The Worker gains a datasource and a **dedicated `worker` schema** containing one
table, `worker.execution_attempt`, with its own Flyway location
(`classpath:db/worker-migration`) and its own history table
(`worker.flyway_schema_history`). The Worker uses `spring-boot-starter-jdbc` +
`JdbcTemplate` (not JPA) and **never** reads or writes any `public` table.

Per `runs.requested`:

1. `INSERT INTO worker.execution_attempt (execution_id, run_id, org_id, status,
   runner_kind) VALUES (?, ?, ?, 'RUNNING', ?) ON CONFLICT (execution_id) DO
   NOTHING` — the pre-execution claim.
   - inserted ⇒ we own the attempt; execute.
   - conflict + row `status = 'COMPLETED'` ⇒ **re-publish the stored terminal
     event** (`terminal_topic` + `terminal_event_json`) and return. Self-healing
     if the API missed the first one; idempotent on the API side.
   - conflict + row `status = 'RUNNING'` and `heartbeat_at < now() - claim-lease`
     ⇒ steal (`attempt_epoch += 1`) and execute; otherwise return without
     executing.
2. publish `runs.started`.
3. iterate cases (heartbeating; honouring the run wall-clock budget and the
   `CancellationToken`); aggregate to a `RunOutcome`.
4. `UPDATE ... SET status='COMPLETED', terminal_topic=?, terminal_event_json=?
   WHERE execution_id=? AND attempt_epoch=?` — **before** publishing. If it
   updates 0 rows we were stolen mid-run: abort the publish.
5. publish `runs.completed` (with a lightweight per-case summary) — or
   `runs.failed` for an interrupt / harness fault, with a generic
   redaction-safe reason.
   - If the ledger itself is unreachable, do **not** publish `runs.failed`:
     rethrow so the Kafka error handler retries (3×1s) then routes to
     `runs.requested.DLT`; the run stays PENDING and is replayable.

A `@Scheduled` sweep deletes rows older than
`qualityops.worker.execution.attempt-retention` (14d). Full lifecycle ownership
of attempts moves to the 2C queue.

`Idempotency-Key: <executionId>` is also sent on non-GET outbound requests as
defense-in-depth; arbitrary targets are not required to honour it (caller risk,
documented).

### 4. Cancellation, timeout, stuck execution

- Per-request timeout = `apiRequest.timeoutMillis` (or a 10s default), clamped to
  `qualityops.worker.execution.max-timeout` (30s). Connect timeout is a separate
  client-level 5s. The JDK client has no distinct read timeout; the single total
  timeout is accepted for basic API tests.
- The send loop is bounded by an **absolute monotonic deadline**
  (`effectiveTimeout` + a 1s grace) so the exchange always terminates even if the
  client's own `HttpRequest.timeout()` does not fire; each poll waits only the
  remaining time and cancels the in-flight future on timeout, cancellation or
  interrupt. It parks between polls — never busy-spins.
- The response body is consumed with a **bounded streaming `BodySubscriber`**
  that retains at most `maxResponseBytes`, then cancels the transfer; the full
  response is never buffered in memory. `responseBodyBytes` is exact when under
  the cap and a lower bound (with `bodyTruncated = true`) when over it; assertions
  and the stored sample see at most the first `maxResponseBytes`.
- `HttpClient.Redirect.NEVER` by default (SSRF-on-redirect and DNS-rebinding
  mitigation). A 3xx is evaluated against `expectedStatus` if set, else FAILED.
- Timeout ⇒ case `TIMEOUT`, run continues, aggregate FAILED. Connection / DNS /
  TLS error ⇒ case `ERROR`, run continues. Run wall-clock budget exceeded ⇒
  remaining cases `ERROR`, run still publishes `runs.completed` FAILED.
- Only a genuine worker fault (`ExecutionHarnessException`) or an interrupt ⇒
  `runs.failed`, with a generic reason.
- Cross-process stuck run: the `RUNNING` claim is reclaimable after `claim-lease`;
  a run stuck with no redelivery has no reaper — deferred to 2D.
- Cancellation is cooperative: a `CancellationToken` polled between cases and
  passed into the runner (which can `future.cancel(true)` a mid-flight request)
  plus thread-interrupt handling. The **signal source** (a `runs.cancelled`
  consumer or a cancel endpoint) is Phase 2D; 2B1 wires `CancellationToken.never()`.

### 5. SSRF-safe target validation

`TargetValidator` (Worker): http/https only; no URL userinfo; optional port
allowlist. The host is resolved and **every** A/AAAA record is checked against a
denylist — loopback, link-local (incl. `169.254.169.254`), site-local,
any-local, multicast, IPv6 ULA `fc00::/7`, CGNAT `100.64/10`, IPv4-mapped IPv6,
and other reserved ranges. `qualityops.worker.execution.ssrf.allow-private-targets`
(default false) unlocks an `allowed-hosts` list for local compose/CI;
`169.254.169.254`, any-local and multicast stay blocked even then. Redirects are
disabled, so per-hop re-validation is not needed (documented if ever enabled).
A blocked target ⇒ case `BLOCKED`, run not aborted. Mapped to OWASP A10:2021.
Residual DNS-rebinding TOCTOU (the JDK client is not IP-pinned) is accepted for
2B1; IP-pinning is a hardening follow-up.

### 6. Structured output + event schema evolution

Worker-internal `CaseExecutionResult` captures redacted request/response
metadata (method, sanitised URL, denylisted headers masked, body **sizes** not
bodies, a truncated+redacted response-body sample plus a `bodyTruncated` flag)
and per-assertion expected/actual (redacted) — shaped for 2B3 artifact storage.

`packages/shared-events`:
- `TestCaseSnapshotItem` gains a nullable `ApiRequestSnapshot`
  (`method, url, headers: List<HttpHeader>, body?, expectedStatus?,
  timeoutMillis?, maxResponseBytes?, assertions: List<ApiAssertion>`);
  `ApiAssertion.Type ∈ {STATUS_EQUALS, BODY_CONTAINS, HEADER_EQUALS,
  JSON_PATH_EQUALS}`. `RunRequestedEvent.SCHEMA_VERSION → 2`.
- `RunCompletedEvent` gains a nullable `List<CaseResultSummary>`
  (`testCaseId, verdict, durationMillis, firstFailureReason?`);
  `SCHEMA_VERSION → 2`. `ResultService` maps these to real `test_results` rows
  and stops fabricating per-case pass/fail; a null list falls back to the legacy
  fabrication path.
- `RunStartedEvent` / `RunFailedEvent`: unchanged.
- Backward compatibility: no field renamed/moved/retyped; additive optional
  nested fields only; `FAIL_ON_UNKNOWN_PROPERTIES=false` ⇒ v1 JSON deserialises
  with the new fields null. `spring.json.trusted.packages: com.qualityops.*`
  still covers everything; **no Kafka config change**. `schemaVersion` is
  advisory (nothing rejects a higher value).

### 7. Authoring the API-request spec (API side, minimal)

`test_cases` gains a nullable `api_request` JSONB column (migration
`V9__add_test_cases_api_request.sql`). `CreateTestCaseRequest` /
`UpdateTestCaseRequest` gain an optional `@Valid` nested `apiRequest` (method
enum via `@Pattern`, `@URL`, size caps on headers/body/assertions, bounded
`expectedStatus` / `timeoutMillis` / `maxResponseBytes`). `TestCase` domain,
`TestCaseEntity`, `TestCaseService`, `TestCaseResponse` thread it through.
`RunService.trigger` freezes it into `config_snapshot` and onto
`RunRequestedEvent` via the existing `toWireSnapshot` mapper. No new endpoints —
`POST/PUT /api/v1/suites/{suiteId}/cases` and `/api/v1/cases/{id}` are reused.

## Amendment to ADR-002 §4

ADR-002 §4 said the Worker has "no datasource, no JPA, no Flyway, no PostgreSQL
driver." This ADR narrows that: the Worker **does** get a datasource, a
PostgreSQL driver, Flyway, and `spring-boot-starter-jdbc` (not JPA). Its reach is
strictly limited:

- a **dedicated `worker` schema** (dev/compose: same server; production: a
  separate database via `DB_URL` — a config change, not a code change);
- exactly **one table**, `worker.execution_attempt`, with its own Flyway
  location and history table;
- **no access** to `public.test_runs`, `public.test_results`,
  `public.test_cases`, or any other API-owned table. `WorkerPersistenceIsolationIT`
  asserts the running schema and that every `JdbcExecutionAttemptStore` SQL
  constant targets only `worker.execution_attempt`.

## How ADR-002's "API is the sole writer of authoritative state" is preserved

`worker.execution_attempt` is a **side-effect guard**, not a system of record:

- Authoritative run status is still only ever written by the API's org-scoped +
  `executionId`-scoped conditional `UPDATE`, driven by lifecycle events.
- Authoritative results are still only ever written by the API's
  `ResultService`, guarded by the org-ownership check, the new `executionId`
  check, `existsByRunId`, and `uq_test_results_run_case`.
- If `worker.execution_attempt` were dropped entirely, the system stays correct:
  the worst case is one extra execution (extra outbound HTTP), after which the
  API's conditional writes still converge state and reject duplicate rows. The
  ledger reduces *duplicate external side effects*; it never *defines* run or
  result truth.

## Consequences

### Positive
- Real API tests execute end to end; the dashboard shows real per-case verdicts,
  durations and (redacted) failure reasons instead of fabricated ones.
- Duplicate delivery — including after a Worker restart — no longer double-fires
  the HTTP call: the claim is durable and the terminal event is cached and
  re-emitted.
- Every lifecycle transition is guarded by `executionId`; stale/superseded
  attempts are inert.
- SSRF is blocked at the app layer (OWASP A10); credentials and secrets are
  redacted from logs, events and stored output; raw bodies are never persisted
  and response memory is bounded to `maxResponseBytes`.
- Event contract stays wire-compatible with v1; rolling deploys degrade safely
  to simulated / legacy paths.
- The Worker's DB reach is one table in its own schema — a Worker bug still
  cannot corrupt authoritative state.

### Negative
- The Worker now has a database dependency: a new starter, driver, Flyway stream,
  and an operational concern (connection pool, migrations, retention sweep).
- ADR-002's clean "database-free Worker" property is softened.
- More moving parts in the Worker: runner port + resolver, validator, redactor,
  assertion evaluator, attempt store, HTTP client config, bounded body handler.
- Two more migrations in `apps/api` (V8, V9) and one in the Worker; `TestRun`,
  `RunEntity`, `RunRepository`, `TestCase` signatures change, rippling through
  tests.
- `RunRequestedEvent` / `RunCompletedEvent` re-serialise a slightly larger
  payload.

### Risks
- **DNS-rebinding TOCTOU:** the JDK `HttpClient` re-resolves and is not pinned to
  the validated IP. Mitigated by "all resolved addresses must pass" + disabled
  redirects + default DNS cache TTL. Hardening (IP-pinned client with Host/SNI)
  is a follow-up.
- **Crash mid-execution + lease timeout:** a crashed Worker's `RUNNING` claim is
  stolen after `claim-lease` and the attempt re-executes — a bounded, intentional
  re-execution that can double-fire a non-idempotent target. `Idempotency-Key`
  helps only for cooperating targets.
- **Stuck RUNNING with no redelivery:** still has no reaper (as in ADR-002).
  Deferred to 2D.
- **Redaction gaps:** pattern-based body redaction is best-effort; never storing
  raw bodies is the backstop.
- **Full body still crosses the socket:** the bounded handler caps *memory* and
  cancels once the cap is hit, but bytes up to that point are still read; the
  send-loop deadline bounds how long that can take.
- **Shared-Postgres coupling in dev:** Worker and API migrate the same server
  (different schemas, different history tables). Production can split via
  `DB_URL`. Reversible by reverting both artifacts.
- **Rolling-deploy skew:** old API + new Worker ⇒ v1 events ⇒ everything
  simulated; new API + old Worker ⇒ old Worker ignores `apiRequest` / emits no
  `caseResults` ⇒ simulated + legacy fabrication. Both safe; no DLT (package name
  unchanged, unlike ADR-002's rename risk).

## Alternatives considered

### Duplicate prevention

- **API owns `execution_attempts`; Worker claims via HTTP back to the API.**
  Rejected: a synchronous Worker→API call in the execution path is the coupling
  ARCHITECTURE #9 forbids and makes API availability a hard dependency of
  execution.
- **Kafka exactly-once (transactional read-process-write).** Rejected as
  insufficient: it makes offset+produce atomic but not the external HTTP side
  effect; a crash after the call and before commit still re-executes. Adds
  `transactional.id` management.
- **Redis `SET NX`.** Rejected as the durability basis: ARCHITECTURE #3 defines
  Redis as ephemeral; a flush replays side effects. Acceptable only as an
  optional cache in front of the Postgres claim — not needed now.
- **Keep the in-memory `ProcessedRunTracker`.** Rejected: lost on restart; fine
  for a sleep, not for real HTTP.
- **Worker uses full JPA / `spring-boot-starter-data-jpa`.** Rejected: heavier,
  entity boilerplate for one table, and it invites future reads of `public`.
  `JdbcTemplate` on one table is the minimal choice.
- **Manual `schema.sql` instead of Flyway for the Worker table.** Rejected:
  violates ARCHITECTURE decision #6 (all schema changes versioned via Flyway).

### Execution-attempt identity

- **`run_executions` child table now.** Rejected as premature: 2C owns the
  attempt/queue model. A 1:1 column is the minimal reversible step.

### Per-case data delivery

- **Keep per-case verdicts entirely in 2B2 (`results.chunk`); `runs.completed`
  stays aggregate-only.** Rejected: `ResultService` would keep fabricating
  per-case results for a whole increment while real data exists and is
  discarded. A small additive event field is less harmful. `results.chunk`
  (live progress + full metadata) remains valuable and complementary in 2B2.

### HTTP client

- **Apache HttpClient / `RestClient`.** Rejected: extra dependency, harder
  per-hop / SSRF interception, no benefit for basic calls.
- **`WebClient`.** Rejected: drags WebFlux/Reactor into a non-reactive Worker.

### Runner port shape

- **Port takes the whole `RunRequestedEvent` and iterates cases.** Rejected:
  duplicates claim / cancellation / budget / aggregation into every adapter. A
  per-case port keeps `ApiExecutionRunner` a pure function of one request.
