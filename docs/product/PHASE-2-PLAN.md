# Phase 2 Plan — Core Platform (2A–2F)

Derived from `ROADMAP.md` Phase 2; six independently shippable increments. Do
one at a time, `planner → implementer → reviewer`. Each increment ends green on
`mvn -B -ntp verify` and `mvn -B -ntp -DskipITs verify` and does not pull work
forward from a later increment.

---

## 2A — Extract the Worker app

**Goal:** Move the Kafka consumers and the simulated-execution / result-generation
logic out of `apps/api` into a new `apps/worker` Spring Boot app that shares the
same PostgreSQL and Kafka; behaviour must not change. Realises ARCHITECTURE.md
decision #1 and preserves decision #2 (API never calls the Worker directly — it
only publishes `runs.requested`).

**Deliverables:**
- New Maven module `apps/worker` (child of the root pom), package
  `com.qualityops.worker`, `WorkerApplication` + `application.yml` (same DB,
  `spring.flyway.enabled=false`, `ddl-auto=validate`, Kafka consumer config
  identical incl. `spring.json.trusted.packages=com.qualityops.*`, actuator
  health only).
- Relocate `RunRequestedConsumer`, `RunCompletedConsumer`, `KafkaConsumerConfig`
  (DLT), the `processRunRequested` simulation path, `ResultService.generateResults`
  + needed JPA adapters into `apps/worker`.
- API keeps `RunKafkaPublisher` + `POST /api/v1/runs`, drops all `@KafkaListener`
  beans.
- Shared events: prefer **Option A** — promote `packages/shared-types` to a real
  module `qualityops-shared-events` holding `RunRequestedEvent`,
  `RunCompletedEvent`, `TestCaseSnapshotItem`, `RunStatus`, depended on by api +
  worker (write ADR); **Option B** = duplicate records in
  `com.qualityops.worker.event` with aligned `spring.json` type mapping (needs
  ADR justification).
- Wire worker service into `docker-compose.yml` + `docker-compose.dev.yml` using
  existing `infra/docker/Dockerfile.worker`.
- Keep idempotency guarantees (conditional `transitionStatus` UPDATE,
  `existsByRunId`, `uq_test_results_run_case`).

**Tests:**
- Worker unit `RunRequestedConsumerTest` + execution-simulation service test
  (port `processRunRequested_*` cases from `RunServiceTest`).
- Worker IT `WorkerOrchestrationIT` (`@EmbeddedKafka` + Testcontainers
  `postgres:16-alpine`) — publish `RunRequestedEvent`, assert terminal status in
  `{PASSED, FAILED}`, one result row per snapshot case, exactly one
  `runs.completed`; then same event twice → no dup side effects; multi-tenancy
  assertion on `test_results.org_id`.
- API: replace moved consumer tests; add IT that `POST /api/v1/runs` publishes
  `runs.requested` and the api no longer consumes it.

**Exit check:** `docker compose up` runs api + worker + infra; a run through the
gateway goes `PENDING → RUNNING → PASSED/FAILED` with results, api logs show no
consumer activity; `mvn verify` green across api, worker, gateway, shared module;
`mvn -DskipITs verify` green.

---

## 2B — Real test runners + artifact storage + retry

**Goal:** Replace the simulation with real HTTP and browser test execution,
persist artifacts, and support configurable retries.

**Deliverables:**
- The per-case `ExecutionRunner` port + `ApiExecutionRunner` (JDK `HttpClient`) +
  `BrowserExecutionRunner` (Playwright for Java) — **delivered by ADR-003 / ADR-004**.
- Per-case streaming (**2B3, ADR-005**): Worker publishes one `ResultChunkEvent` on
  the new `results.chunk` topic per case; the API result module consumes it and the
  v4 terminal through the *same* epoch-monotone upsert into `test_results` +
  `test_result_artifacts`.
- `ArtifactStoragePort` + `S3ArtifactStorage` (MinIO Java client) — **2B3, ADR-005**.
  Org-first path-addressed keys, SSE-S3, retention lifecycle rule; private bucket;
  API-side presigned GET with a **separate read-only** credential; MinIO +
  `minio-bootstrap` services in compose.
- Migration **`V11__create_test_result_artifacts_and_result_attempt_epoch.sql`**
  (`test_result_artifacts` table + 4 indexes incl. the idempotent-upsert unique
  key; `ALTER TABLE test_results ADD COLUMN attempt_epoch`).
- **Bounded in-run retry** (**2B3, ADR-005**): `RunExecutionService.runCases`
  re-runs a transient `TIMEOUT`/`ERROR` with `SideEffectClass.NONE_OBSERVED` and
  budget room, up to `retry.max-attempts`; `test_results.retry_count` = final
  `attempt_epoch`. Queue-driven retry is 2C.
- `ResultChunkEvent` on `results.chunk` (`SCHEMA_VERSION = 1`; `orgId` + timestamp
  + `attemptEpoch` + `ArtifactReference[]`).
- `secretRef` credential indirection (**2B3, ADR-005**): `HttpHeader.secretRef` /
  `BrowserStep.secretValue`; `EnvFileSecretResolver` at execution time.

**Key new files:** `apps/worker` `execution/adapter/out/storage/{S3ArtifactStorage,MinioArtifactClientConfig}.java`,
`execution/application/service/{ArtifactUploadService,ArtifactStagingSweeper}.java`,
`config/BucketBootstrap.java`, `execution/adapter/out/secret/EnvFileSecretResolver.java`;
`apps/api` `result/adapter/in/messaging/ResultChunkConsumer.java`,
`result/application/service/ArtifactService.java`, artifact download endpoints
`GET /api/v1/runs/{id}/artifacts` + `GET /api/v1/artifacts/{id}`; `V11`.

**Tests:** runner adapter ITs vs MockWebServer (API) + static page (Playwright
headless CI); `MinioArtifactStorage` IT with MinIO Testcontainer (put → presigned
get 200, wrong-org key → 404); `ResultChunkConsumer` idempotency IT (dup chunk →
one row); retry IT (fail twice then pass → `retry_count=2`, final `PASSED`).

**Exit check:** a suite with one real HTTP case + one real browser case runs
end-to-end in compose, artifacts downloadable via presigned URL, failed case
retried per config; `mvn verify` green.

---

## 2C — Scheduling, leader coordination, queue state, priorities, tenant fairness

> **✅ COMPLETE — 2026-09-03.** Authoritative record: `docs/architecture/decisions/006-scheduling-and-queue.md`
> and its *2C design-point resolutions & audit follow-ups* amendment. The plan
> text below is superseded by the ADR where they differ: migrations are **V12–V15**
> (not V9/V10); `run_queue.priority` / `queue_state` are **VARCHAR + CHECK** (not PG
> enums); priority is resolved by a **DB-ordered dispatcher with an aging boost**
> (not `runs.requested.high|normal|low` topics); queued-run **list + cancel** (incl.
> the cooperative `runs.cancel` path + Worker `CancellationRegistry`) and the
> Micrometer **queue meters** landed in 2C, so 2D's deliverable list narrows to the
> stuck-run reaper, queue-driven (re-published) retries, the `GET /api/v1/admin/queue`
> summary + Grafana JSON, the `org_run_concurrency` write path, the CI execution
> API, and the Caseflow contract.

**Goal:** Schedule runs, coordinate dispatch across API replicas, and give the
queue authoritative state with priorities and per-tenant fairness.

**Deliverables:**
- Scheduling module in `apps/api` (one-time + cron, timezone, pause/resume,
  duplicate-trigger protection); endpoints
  `POST/GET/PUT/DELETE /api/v1/projects/{projectId}/schedules`,
  `GET/PUT/DELETE /api/v1/schedules/{id}`.
- ShedLock + Postgres (shedlock table via `V10`) so one API replica dispatches a
  due schedule.
- `V9__create_run_queue.sql`: `run_queue(id, org_id, run_id FK, priority enum
  high/normal/low, state queued/dispatched/cancelled, enqueued_at, dispatched_at)`;
  Postgres owns queue state, Kafka carries the immutable job.
- Priority topics `runs.requested.high|normal|low` (or priority header + partition
  strategy) with starvation protection (weighted drain).
- Per-tenant concurrency: config `max_active_runs` per org; dispatcher counts
  active runs per `org_id` before dispatch; fair round-robin across orgs.

**Key new files:** `apps/api` `scheduling/**` (controller/service/domain/adapters/
`ScheduleEntity`); `execution/application/service/QueueDispatchService.java`;
`execution/adapter/out/persistence/RunQueue*`; `V9`, `V10`; ShedLock dep in
`apps/api/pom.xml`.

**Tests:** cron-next-fire unit (timezones, DST); ShedLock IT two contexts →
dispatched once; queue-priority IT (high jumps normal, low not starved under
sustained high); tenant-fairness IT (org A floods, org B still dispatched within
cap); duplicate-schedule-trigger IT.

**Exit check:** a cron schedule fires once across 2 API replicas, produces a
queued run respecting org `max_active_runs`; priority ordering observable;
`mvn verify` green.

---

## 2D — Queue controls, observability, CI execution API, Caseflow contract

> **✅ COMPLETE — 2026-09-03.** Authoritative record:
> `docs/architecture/decisions/007-queue-reaper-retry-ci-caseflow.md`. The plan
> text below is superseded by the ADR where they differ: the CI dedupe table is
> **`V17__create_ci_idempotency_key.sql`** (not `V11`, long taken), plus **V16**
> (`run_queue.retry_of`/`retry_count`) and **V18** (`webhook_endpoint`,
> `webhook_delivery`); `RunCancellationService` and queued-run **list + cancel**
> already landed in **2C** (ADR-006 §5), so 2D's cancel work was only the
> **stuck-run reaper** (stranded-`DISPATCHED` re-publish + stuck-`RUNNING`→`FAILED`)
> and **queue-driven retry**; `common/IdempotencyKeyStore.java` is instead a
> dedicated `ci_idempotency_key` table + repository; the webhook sender lives in a
> new `com.qualityops.api.webhook` module with a `webhook_delivery` outbox +
> ShedLock `webhook-dispatch` job. No `shared-events` change, no Worker change,
> no new Kafka topic.

**Goal:** Operate the queue (list/cancel), expose queue metrics, and give CI
systems an idempotent execution API plus a versioned Caseflow contract with
signed webhooks.

**Deliverables:**
- Queued-run controls `GET /api/v1/runs?state=queued`,
  `POST /api/v1/runs/{id}/cancel` (only while queued/dispatched; Worker re-checks
  cancellation before execution; runs immutable once started).
- Queue observability: Micrometer metrics — consumer lag, queue depth,
  oldest-job age, wait time, throughput; `GET /api/v1/admin/queue` summary;
  Grafana panel JSON in `infra/`.
- CI execution API: idempotent `POST /api/v1/ci/runs` keyed by `Idempotency-Key`
  header (dedupe table `V11__create_idempotency_keys.sql`, unique
  `(org_id, idempotency_key)`); scoped CI tokens minimal (reuse JWT now, full
  scoped tokens Phase 4); adapters/docs for Jenkins, GitLab CI, GitHub Actions.
- Caseflow contract: versioned OpenAPI `docs/api/caseflow-v1.yaml` (submit /
  status / cancel / results) + signed completion webhooks (HMAC-SHA256,
  `X-QualityOps-Signature`, timestamp, replay window); webhook-sender service in
  `apps/api`.

**Key new files:** `apps/api`
`execution/adapter/in/web/{CiRunController,QueueAdminController}.java`;
`execution/application/service/RunCancellationService.java`;
`webhook/{WebhookSender,WebhookSignature}.java`;
`common/IdempotencyKeyStore.java`; `V11`; `docs/api/caseflow-v1.yaml`.

**Tests:** idempotent-submit IT (same key twice → one run, 200 both);
cancel-before-dispatch IT + Worker-honours-cancel IT; webhook signature unit
(valid, tampered body, stale timestamp) + delivery IT vs MockWebServer;
queue-metrics IT asserting gauges registered.

**Exit check:** a GitHub Actions job hits `POST /api/v1/ci/runs` twice with one
key → single run, polls status, receives signature-verified completion webhook;
operator cancels a queued run; `mvn verify` green.

---

## 2E — Analytics, real-time, app-level limits, AOP, HTTPS staging, CI scanning

> **✅ COMPLETE — 2026-09-04.** Authoritative record:
> `docs/architecture/decisions/008-analytics-realtime-aop-hardening.md`. The plan
> text below is superseded by the ADR where they differ: flaky detection uses an
> **on-the-fly native window query, no `test_case_stats` table** — `V19` is an
> analytics-index migration; environment health is **V20** (adds
> **`environments.health_status`**, a new `VARCHAR + CHECK` column — **not**
> transitions on the existing `environment_status` PG enum) + an
> `environment_health_check` history table; audit is **V21** (`audit_log`).
> Application rate limiting is a **`HandlerInterceptor`**, not an aspect. Audit
> lives in a dedicated **`com.qualityops.api.audit` module**, not `common/audit/`.
> `spring.task.scheduling.pool.size` `4 → 5` (fifth job `environment-health-probe`).
> Verified: `mvn verify` (per-module: `-DskipITs` across all 4; ITs run
> batched — api 402 IT + 224 unit, worker full incl. browser, gateway incl.
> `GatewayStagingProfileIT`); frontend lint/typecheck/vitest/build +
> `npm audit --audit-level=high --omit=dev`; `docker compose` full stack +
> Playwright `e2e/smoke.spec.ts`.

**Goal:** Deliver flaky detection and duration analytics, live dashboard updates,
per-org operation limits, cross-cutting AOP concerns, HTTPS in staging, and
security scanning in CI.

**Deliverables:**
- Flaky detection (stability score over last N runs per `test_case_id`;
  `GET /api/v1/analytics/flaky`; `V12` if materialised `test_case_stats`).
- Duration trends `GET /api/v1/analytics/trends`, `GET /api/v1/analytics/slow`
  (from `test_results.duration_ms`).
- Environment health monitoring: scheduled probe per environment `base_url`;
  `environments.status` transitions; history table `V13`.
- Redis dashboard cache: cache-aside for analytics/list reads (TTL 30s),
  invalidate on `runs.completed`.
- WebSocket `/ws/runs/{id}` pushing status/progress from `runs.*` events
  (replaces polling); Redis pub/sub fan-out across API replicas.
- Application-level rate limiting: Redis counters per `org_id` per operation
  (runs/hour, AI/hour later); 429 + `Retry-After`.
- Spring AOP: `@Audited` (audit rows) + `@Timed` (slow-op metrics) annotations +
  aspects; ADR on self-invocation limitation.
- HTTPS in staging: TLS termination at LB/ingress; HSTS already emitted by
  gateway.
- CI scanning: OWASP Dependency-Check (mvn) + `npm audit --audit-level=high` +
  Trivy image scan jobs in `ci.yml` (fail on high/critical).

**Key new files:** `apps/api` `result/**` flaky/trends services + endpoints;
`environment/application/service/EnvironmentHealthService.java`;
`config/{RedisCacheConfig,WebSocketConfig}.java`;
`common/ratelimit/{RateLimited,RateLimitAspect}.java`;
`common/audit/{Audited,AuditAspect,Timed,TimingAspect}.java`; `V12`/`V13`; CI job
additions.

**Tests:** flaky-score unit (alternating pass/fail → high; all-pass → 0); cache
IT (2nd read from Redis, invalidated on completion event); WebSocket IT
(`@SpringBootTest` + `WebSocketStompClient`, receives status frame on
completion); app rate-limit IT (N+1th run in the hour → 429); AOP
proxy-behaviour tests (`@Around`/`@Before` ordering, exception propagation,
annotation pointcut hits, self-invocation bypasses proxy); CI scanning jobs run
on a PR.

**Exit check:** dashboard updates live over WebSocket, flaky report meaningful on
seeded data, `@Audited`/`@Timed` covered by proxy tests, staging serves HTTPS, CI
fails on a planted vulnerable dependency; matches ROADMAP Phase 2 exit criteria.

---

## 2F — Repository-owned framework execution

> **🚧 IMPLEMENTATION COMPLETE — verification pending (2026-09-04).**
> Authoritative record: `docs/architecture/decisions/009-repository-owned-framework-execution.md`.
> All code, migrations (`V22`–`V25`), frontend, docs, and CI additions have
> landed and each work package's own gates (`mvn verify` per module,
> `docker compose config`, `apps/web` lint/typecheck/vitest/build) are green.
> **Not yet run: a full-stack `docker compose up` against the WP9
> network-split topology (`qualityops-internal`/`qualityops-runner-egress` +
> `docker-proxy`) and the `repository-run` Playwright E2E smoke** — that
> full-stack verification pass is tracked separately and this section should
> not be marked ✅ COMPLETE until it passes.
>
> **Scope decision (2026-09-04): suite-authored only.** A repo test case is
> authored via the case editor's "Repository" tab and runs through the
> existing suite Run-now / CI / schedule flows. There is **no** ad-hoc
> "run now from a connection" endpoint (`POST .../repository-runs` below is
> **not implemented** — `test_runs.suite_id` stays `NOT NULL FK`, no
> `test_runs` migration in 2F). The plan text below is superseded by the ADR
> where they differ.

**Starts only after 2E is complete.**

**Goal:** Let users connect a GitHub/GitLab repository and launch its existing
Playwright, JUnit, pytest, Cypress, or k6 project from the QualityOps UI, CI API,
or scheduler. Repository code is untrusted and must execute only in an isolated,
disposable runner.

**Deliverables:**
- Repository connections scoped by `org_id` and project: provider, canonical
  repository identity, default ref, and `credentialRef`; never persist a
  plaintext provider token.
- Repository test specification: framework preset, requested branch/tag/commit,
  working directory, argument-vector test command, environment/secret
  references, timeout, resource profile, report paths, and artifact globs.
- Resolve a mutable branch or tag to an immutable commit SHA before the run is
  created; freeze the repository identity, commit SHA, command, and execution
  settings in the run snapshot.
- UI flows to connect/test a repository, configure repository tests, select
  **Run now**, attach a schedule, and display checkout/execution progress,
  parsed test items, logs, and artifacts.
- Extend the existing runner-selection model with a repository runner kind and
  keep SCM operations and container execution behind ports.
- A local Docker execution adapter launches one fresh container per attempt.
  Phase 5 supplies a Kubernetes Job/VM adapter behind the same port.
- Parse standard JUnit XML and supported framework reports into normalized
  repository test-item results while retaining the run-level result.
- Reuse the Phase 2C queue, priority, tenant-concurrency, cancellation,
  idempotency, and retry controls; reuse Phase 2B3 artifact and `secretRef`
  flows.

**Isolation requirements:**
- Never execute repository code, install dependencies, or invoke its command in
  the API or long-lived Kafka Worker process.
- Allowlisted, digest-pinned runner images only; no user-provided image in 2F.
- Non-root user, no privileged mode, all Linux capabilities dropped, read-only
  root filesystem, bounded writable workspace, CPU/memory/PID/disk/time limits,
  and unconditional cleanup.
- Runner network is isolated from Postgres, Redis, Kafka, MinIO control
  credentials, and Docker control APIs. Outbound access is denied by default
  and enabled only through explicit target/dependency policies.
- Checkout credentials are short-lived where supported. Resolve secrets only at
  execution time and redact them from process arguments, logs, reports, events,
  and artifacts.
- Validate provider/repository URLs against an allowlist to prevent SSRF and
  record exact commit, runner image digest, framework, timestamps, and exit code
  for provenance.

**Architecture documentation before implementation:**
- Write a repository-framework-execution ADR using the next available ADR
  number (Phase 2D is expected to use ADR-007).
- Update `ARCHITECTURE.md` with the repository-run flow and the container-runner
  port boundary.
- Update shared event schemas additively and retain backward-compatibility
  tests; choose migration numbers from the repository state at implementation
  time rather than reserving them here.

**Tests:**
- Unit tests for ref-to-commit resolution, snapshot immutability, runner
  selection, report parsing, redaction, path validation, and cancellation.
- Testcontainers/Docker integration test: clone a fixture repository at an
  exact commit, run it in an approved image, parse results, upload artifacts,
  and clean the workspace.
- Duplicate Kafka delivery launches at most one container; a restart can safely
  reconcile an in-flight attempt.
- Security integration tests prove the runner cannot reach application data
  services, use an unapproved image, exceed configured resources, escape its
  workspace, or leak injected secrets.
- End-to-end UI test: connect fixture repo → configure command → run now →
  queued/running/terminal updates → inspect item results and artifact.
- Scheduled-run E2E proves two fires of the same configuration each retain their
  own immutable commit snapshot.

**Exit check:** The fixture repository can be launched from both the UI and a
schedule, executes at the frozen commit in a disposable local Docker runner,
and reports normalized results/artifacts through existing QualityOps APIs.
Cancellation and duplicate delivery are safe, all isolation tests pass, root
Maven/frontend/Compose/Playwright verification is green, and no untrusted code
runs in a long-lived service.

---

## Appendix: Phase 2A kickoff prompt

> Task: Phase 2A — Extract the Worker app. Scope is strictly 2A from docs/product/PHASE-2-PLAN.md. Do NOT implement 2B, 2C, 2D, or 2E (no real test runners, no artifact storage, no scheduling, no queue tables, no analytics, no WebSocket, no AOP). Do not implement any Phase 4/4B/5/6 feature. Follow planner → implementer → reviewer. Load the java-spring and kafka-redis skills; obey .claude/rules/java-backend.md, kafka-events.md, tests.md, docker-infra.md, ci-cd.md. Goal: move the Kafka consumers and the simulated-execution / result-generation logic out of apps/api into a new apps/worker Spring Boot app sharing the same PostgreSQL and Kafka; behaviour must not change; this realises ARCHITECTURE.md decision #1 and preserves decision #2 (API never calls the Worker directly — only publishes runs.requested). In scope: (1) new Maven module apps/worker (root reactor child), package com.qualityops.worker, WorkerApplication.java, application.yml (same datasource as API, spring.flyway.enabled=false, ddl-auto=validate, Kafka consumer config identical incl spring.json.trusted.packages=com.qualityops.*, actuator health only); (2) relocate RunRequestedConsumer, RunCompletedConsumer, KafkaConsumerConfig (DLT), the processRunRequested simulation path, ResultService.generateResults + JPA adapters into apps/worker; (3) apps/api keeps RunKafkaPublisher + POST /api/v1/runs, remove all @KafkaListener beans; (4) shared event records — prefer Option A (promote packages/shared-types to module qualityops-shared-events with RunRequestedEvent, RunCompletedEvent, TestCaseSnapshotItem, RunStatus, depended on by both apps); if Option B (duplicate in com.qualityops.worker.event) write an ADR and align spring.json type mapping; ADR in docs/architecture/decisions/ either way; (5) add worker service to docker-compose.yml + docker-compose.dev.yml using infra/docker/Dockerfile.worker; (6) keep idempotency guarantees (conditional transitionStatus UPDATE, existsByRunId, uq_test_results_run_case); (7) update ARCHITECTURE.md decision #1/#2 notes, apps/worker/README.md status, CLAUDE.md if any statement is now wrong. Tests required: apps/worker unit RunRequestedConsumerTest + execution-simulation service test porting processRunRequested_* from RunServiceTest; apps/worker integration WorkerOrchestrationIT (*IT.java, @EmbeddedKafka + Testcontainers postgres:16-alpine, method naming methodName_condition_expectedResult): publish RunRequestedEvent, assert terminal status in {PASSED,FAILED}, one result row per snapshot case, exactly one runs.completed; then deliver the same event twice and assert no duplicate side effects; multi-tenancy assertion (test_results.org_id == event orgId). apps/api: replace moved consumer tests; add IT that POST /api/v1/runs publishes runs.requested and apps/api no longer consumes it. Exit criteria: docker compose up runs api + worker + infra; a run triggered through the gateway goes PENDING → RUNNING → PASSED/FAILED with results and apps/api logs show no consumer activity; mvn -B -ntp verify green across apps/api, apps/worker, apps/gateway, shared module; mvn -B -ntp -DskipITs verify green; frontend CI unaffected; no Phase 2B+ code introduced.
