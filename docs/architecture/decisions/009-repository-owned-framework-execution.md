# ADR-009: Repository-owned framework execution (Phase 2F)

## Status

Proposed.

- **Realises** `docs/product/PHASE-2-PLAN.md` §2F and the Phase-2F rows of `docs/product/ROADMAP.md`: connect a GitHub/GitLab repository, resolve a mutable ref to an immutable commit, and run its existing Playwright / JUnit / pytest / Cypress / k6 project from the UI, the CI API, or a schedule — inside an isolated, disposable local Docker runner — with normalized results and artifacts surfaced through the existing QualityOps run APIs.
- **Preserves** every invariant carried from ADR-001…008: multi-tenancy on every row / query / event; the API is the **sole writer** of authoritative relational state (`test_runs`, `test_results`, `run_queue`, and the new `repository_connection` / `repository_run` / `repository_test_item`); hexagonal architecture for complex modules; Flyway append-only (this increment starts at **V22**; current `apps/api` max is V21); every new **table** carries `org_id NOT NULL`; enums are `VARCHAR + CHECK`, never PG enum types (`SchemaMigrationIT` asserts this); runs are immutable once created (domain rule #2); the API never calls the Worker synchronously (domain rule #9); boring, reversible technology.
- **Preserves and re-states** ADR-003 §3 / ADR-005 §preamble — **the Worker's Postgres reach stays exactly `worker.execution_attempt` in its own `worker` schema. There is NO worker Flyway migration in this increment.** Repository-run dedup rides the existing `worker.execution_attempt` claim (`runner_kind='REPOSITORY'`, a free-text `VARCHAR` value, not a schema change); orphan-container recovery uses Docker labels, not a table.
- **Does not touch** the set of Kafka topics (`runs.requested` is reused), the `sealed interface RunEvent` (nothing added), or the `apps/worker` datasource.
- **Extends** ADR-003 §1 (the `ExecutionRunner` port and `RunnerKind` gain a `REPOSITORY` kind), ADR-005 §1/§2/§4 (reuses `ArtifactStoragePort`, `SecretResolver`, the `secretRef` model, the epoch-guarded upsert, and the "gate secret-bearing artifacts" pattern), ADR-006 §3–§5 (a repository run is enqueued, dispatched, priority-aged, tenant-concurrency-capped, and cancelled by the **unchanged** 2C machinery), ADR-007 §1–§2 (the stuck-run reaper and queue-driven retry apply unchanged), ADR-008 §3 (`common/net/OutboundAddressGuard` is reused for the SCM host check) and ADR-008 §6/§7 (`@RateLimited` + `@Audited` on the new mutating endpoints).
- **Touches `packages/shared-events` additively only.** `RunRequestedEvent` `4 → 5` (nested `TestCaseSnapshotItem` gains a nullable `RepoTestSnapshot`), `RunCompletedEvent` `4 → 5` (nested `CaseResultSummary` gains `repositoryItems` + `repositoryProvenance`), `ResultChunkEvent` `1 → 2` (the same two fields). Nothing is renamed or retyped; every new field deserialises null/empty under `FAIL_ON_UNKNOWN_PROPERTIES=false`; `spring.json.trusted.packages: com.qualityops.*` already covers the new records — **no Kafka config change**. `EventBackwardCompatibilityTest` / `EventContractTest` / `EventSerializationRoundTripTest` stay green.
- **Two new runtime dependencies**, both on the Worker only, each justified in §1 / §12: `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5` (the Docker Remote API client) and, for local/compose isolation, the `tecnativa/docker-socket-proxy` image (infra, not a Java dependency).

## Context

After Phase 2E the platform can trigger, schedule, queue, dispatch, execute (real HTTP + declarative browser), retry, reap, webhook-notify, analyse, and live-stream runs. Execution is a per-case loop in `apps/worker`: `RunExecutionService.processRunRequested` claims `worker.execution_attempt`, and for each `TestCaseSnapshotItem` the `ExecutionRunnerResolver` picks a runner by precedence `browserTest > apiRequest > simulated`. Two snapshot kinds exist — `ApiRequestSnapshot` (V9, `test_cases.api_request` JSONB) and `BrowserTestSnapshot` (V10, `test_cases.browser_test` JSONB) — both authored through the reused `POST/PUT /api/v1/suites/{suiteId}/cases` endpoints, both frozen into `RunConfigSnapshot` and `RunRequestedEvent`, both executed with an SSRF guard (`TargetValidator` + `CidrBlockList`) and `Redactor`.

There is **no** way to run a test project that already lives in a Git repository. A user with a mature Playwright or pytest suite in GitHub cannot point QualityOps at it. Phase 2F adds this, under one hard constraint that shapes every decision:

> **Untrusted repository code — its dependency install and its test command — must never execute in the API process or in the long-lived Kafka Worker process.** It runs only in an ephemeral, hardened container that has no route to Postgres / Redis / Kafka / MinIO control credentials / the Docker control API, that is non-root with all Linux capabilities dropped and a read-only root filesystem, and that is destroyed unconditionally after one attempt.

Three secondary constraints:

1. **Maximum reuse of the 2C/2D/2E control plane.** A repository run must be a normal `run_queue` row: priority-aged, tenant-concurrency-capped, cancellable, idempotent under the CI API, retryable, reaped, webhook-notified, and streamed over the WebSocket — with **no fork** of the dispatcher, the reaper, or the retry logic.
2. **The commit is frozen before the run exists.** A mutable ref (`main`, `v2`) is resolved to an immutable 40-hex SHA in the API *before* `test_runs` is inserted; the SHA, repo identity, runner image digest, argv command, and settings are snapshotted (domain rule #2). A retry re-runs the exact same commit; a schedule fire re-resolves the ref.
3. **The container adapter is swappable.** Phase 5 replaces the local Docker adapter with a Kubernetes Job (or VM) adapter behind the same port, with zero change to the queue, the events, or the domain.

Constraints carried from ADR-001…008: multi-tenancy on every event, row, and object key; idempotency under Kafka at-least-once; the API is the sole writer of authoritative state; event-driven, not request-driven, for execution; Flyway append-only; modules communicate through services/ports; no arbitrary user JavaScript or shell in a test definition (a repo's own command is argv, `exec`-style, never `sh -c` from us); a terminal lifecycle event must always be publishable.

---

## Decision

### 1. The Worker hosts the runner as a control-plane driver; repository code runs only in a sibling container

**Decision: the existing `apps/worker` gains a `RepositoryExecutionRunner` (`kind() == REPOSITORY`) behind the unchanged `ExecutionRunner` port. It never runs repository code in-JVM. It orchestrates two fresh, disposable sibling containers per attempt through a new output port `ContainerRunnerPort`: a *checkout* container (`git fetch`s the frozen SHA) and a *framework* container (runs the argv command). Do NOT build a new `apps/runner-orchestrator` service.**

The "long-lived Worker process" prohibition is about *executing untrusted code in-process* — loading repo classes, `Runtime.exec("npm test")`, evaluating repo scripts. `RepositoryExecutionRunner` does none of that. It only speaks the Docker Remote API (create / start / wait / logs / kill / rm) and reads files from a bind-mounted per-attempt workspace directory. This is the same kind of relationship the Worker already has with Chromium (a subprocess rendering untrusted web content, ADR-004), hardened further by a container boundary and network isolation.

**Why not a new `apps/runner-orchestrator` app:** it would duplicate the entire execution substrate the Worker already owns — `KafkaConsumerConfig` + DLT, the `worker.execution_attempt` claim ledger (dedup + lease-steal + cached-terminal re-emit), `ArtifactUploadService` + `ArtifactStoragePort`, `EnvFileSecretResolver`, `CancellationRegistry` + the `runs.cancel` consumer, `RunLifecyclePublisher`, `Redactor`, and the per-case retry loop in `RunExecutionService`. All of it is directly reusable for a repository run. A new deployable buys isolation the container boundary already provides.

**Why not run the command in-JVM under a `chroot` / namespaces from Java:** that *is* executing untrusted code in the long-lived Worker process; the JVM has no real sandbox primitive; it violates the hard isolation requirement outright.

**Blast-radius mitigation (documented, no code cost):** the Worker resolves runners from a `List<ExecutionRunner>` and reads `WORKER_EXECUTION_MODE`. A deployment that wants repository runs off the browser/API Worker can run a second `worker` deployment of the *same image* with only `REPOSITORY` enabled (`qualityops.repo-exec.enabled=true`, browser/API paths disabled) and the browser/API Worker with `qualityops.repo-exec.enabled=false`. This is a topology change, not a redesign, and is the natural precursor to the Phase-5 split.

**Port boundary and the Phase-5 swap point** — new Worker output port `com.qualityops.worker.execution.application.port.out.ContainerRunnerPort`:

```java
public interface ContainerRunnerPort {

    /** Create ONE container from an allowlisted, digest-pinned image, start it,
     *  stream stdout/stderr to the sink, wait up to spec.timeout(), return the
     *  exit result. NEVER pulls or runs a non-allowlisted image. Enforces every
     *  HostConfig limit in §6. */
    ContainerRunResult run(ContainerRunSpec spec, LogSink logs, CancellationToken cancel)
            throws ContainerRunException;

    /** Best-effort unconditional teardown: force-remove any managed container +
     *  the per-attempt workspace directory for this executionId. Called from a
     *  finally and on a cooperative cancel. */
    void cleanup(java.util.UUID executionId);

    /** Startup + periodic: force-remove managed containers whose attempt is
     *  COMPLETED, or older than the run wall-clock budget, or not in
     *  liveExecutionIds. Returns the count removed. */
    int sweepOrphans(java.util.Set<java.util.UUID> liveExecutionIds);
}

record ContainerRunSpec(
        java.util.UUID executionId, int attemptEpoch, String phase,     // "checkout" | "framework"
        String imageRef, java.util.List<String> entrypoint, java.util.List<String> command,
        String workingDir, java.util.Map<String,String> env,
        java.nio.file.Path workspaceHostDir, ResourceLimits limits,
        NetworkMode network, java.time.Duration timeout,
        java.util.Map<String,String> labels) {}

record ResourceLimits(long memoryBytes, long nanoCpus, int pidsLimit,
                      long tmpfsBytes, long workspaceBytes,
                      long nofileSoft, long nofileHard) {}

enum NetworkMode { NONE, EGRESS }

record ContainerRunResult(int exitCode, boolean timedOut, boolean cancelled,
                          java.time.Instant startedAt, java.time.Instant finishedAt) {}
```

`DockerContainerRunner` (`adapter/out/container/DockerContainerRunner.java`) implements it with `docker-java`. Phase 5's `KubernetesJobRunner` implements the **same interface** — `RepositoryExecutionRunner`, `ExecutionRunnerResolver`, `RunExecutionService`, `run_queue`, the events, and the API are untouched. `ScmPort` (§4) is the second Phase-5-stable seam (SCM REST stays REST).

**Rolling-deploy skew guard:** an old Worker with no `REPOSITORY` runner must not silently mis-handle a v5 `runs.requested` carrying a `repoTest`. `ExecutionRunnerResolver.resolve` returns a runner or, when `REPOSITORY` is unregistered, a sentinel that makes the case `BLOCKED "repository execution unavailable on this worker"` (`qualityops.repo.blocked{reason=worker_unavailable}`) — never an NPE, never a simulated fallback. Deploy API + Worker together, as ADR-002…008 already require.

---

### 2. Kafka: reuse `runs.requested`; add `RepoTestSnapshot` additively; bump `RunRequestedEvent` to v5

**Decision: no new topic. A repository run is a normal `RunRequestedEvent` whose frozen snapshot contains exactly one `TestCaseSnapshotItem` with a non-null `repoTest`. `TestCaseSnapshotItem` gains a 6th component `RepoTestSnapshot repoTest` (nullable); `RunRequestedEvent.SCHEMA_VERSION 4 → 5`. Per-test framework results ride the existing `results.chunk` and the terminal via two additive fields on `CaseResultSummary` and `ResultChunkEvent`.**

Reusing `runs.requested` means the ADR-006/007 control plane works unchanged: `RunEnqueueService` (single admission point) freezes the snapshot into `test_runs.config_snapshot` and `run_queue.requested_event_json`; `QueueDispatchJob` priority-orders and tenant-caps it; `RunCancellationService` + `runs.cancel` + `CancellationRegistry` cancel it; `RetryRunService` retries a `runs.failed`; `StuckRunReaper` reaps it; `WebhookDispatchJob` notifies; the `realtime` module streams it. A new topic would fork every one of those.

A repo run is conceptually **one unit of execution** (one container running `npm test`), not N independent cases — and the test list is unknown until it runs. So the frozen snapshot carries exactly **one** `TestCaseSnapshotItem` whose `repoTest` is set; the Worker produces a single run-level `CaseExecutionResult`; the framework report is parsed into N `RepositoryTestItem`s carried alongside.

**New shared records / enums** (`packages/shared-events`, `com.qualityops.events`, all covered by the existing trusted-packages glob):

```java
public record RepoTestSnapshot(
    UUID repositoryConnectionId,
    RepositoryProvider provider,          // GITHUB | GITLAB
    String repoHost, String repoPath,     // canonical identity — "github.com", "owner/name"
    String requestedRef,                  // branch/tag/short-sha as authored
    String commitSha,                     // RESOLVED 40-hex — frozen, immutable (§3, domain rule #2)
    RepoRefType refType,                  // BRANCH | TAG | COMMIT
    FrameworkPreset framework,            // PLAYWRIGHT | JUNIT | PYTEST | CYPRESS | K6
    String runnerImageRef,               // digest-pinned, frozen from the API allowlist (§5)
    String workingDir,                    // nullable
    List<String> command,                // argv — never null/empty, never shell
    RepoReportFormat reportFormat,        // JUNIT_XML | K6_SUMMARY_JSON
    List<String> reportPaths,             // globs, workspace-relative
    List<String> artifactGlobs,           // globs, workspace-relative
    List<EnvVar> environmentVars,         // EnvVar(name, value) — non-secret
    List<SecretEnvVar> secretVars,        // SecretEnvVar(name, SecretRef ref) — resolved by the Worker
    String credentialRef,                 // opaque [A-Z0-9_]{1,64}, nullable (public repo); resolved by the Worker
    RepoResourceProfile resourceProfile,  // SMALL | MEDIUM | LARGE
    RepoNetworkPolicy networkPolicy,      // ISOLATED | EGRESS
    int timeoutSeconds
) {}

public record EnvVar(String name, String value) {}
public record SecretEnvVar(String name, SecretRef ref) {}          // SecretRef already exists (ADR-005)

public record RepositoryTestItem(
    String suite, String name,            // framework classname/describe path + test name
    RepoItemStatus status,                // PASSED | FAILED | SKIPPED | ERROR
    long durationMillis,
    String failureType,                   // nullable
    String failureMessage                 // nullable, PRE-REDACTED + truncated by the Worker
) { public enum RepoItemStatus { PASSED, FAILED, SKIPPED, ERROR } }

public record RepositoryRunProvenance(
    String imageDigest, Integer exitCode,
    int itemsTotal, int itemsPassed, int itemsFailed, int itemsSkipped,
    java.time.Instant checkoutAt, java.time.Instant startedAt, java.time.Instant finishedAt
) {}

public enum RepositoryProvider { GITHUB, GITLAB }
public enum RepoRefType { BRANCH, TAG, COMMIT }
public enum FrameworkPreset { PLAYWRIGHT, JUNIT, PYTEST, CYPRESS, K6 }
public enum RepoReportFormat { JUNIT_XML, K6_SUMMARY_JSON }
public enum RepoResourceProfile { SMALL, MEDIUM, LARGE }
public enum RepoNetworkPolicy { ISOLATED, EGRESS }
```

**Carrier changes:**

- `TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex, ApiRequestSnapshot apiRequest, BrowserTestSnapshot browserTest, RepoTestSnapshot repoTest)` — a 5-arg convenience ctor keeps every current call site compiling (`repoTest = null`), on top of the existing 3-arg / 4-arg ctors.
- `CaseResultSummary(... , int attemptEpoch, List<ArtifactReference> artifacts, List<RepositoryTestItem> repositoryItems, RepositoryRunProvenance repositoryProvenance)` — a 6-arg convenience ctor keeps existing call sites compiling (`repositoryItems = List.of()`, `repositoryProvenance = null`).
- `ResultChunkEvent(... , List<ArtifactReference> artifacts, List<RepositoryTestItem> repositoryItems, RepositoryRunProvenance repositoryProvenance)` — additive; `SCHEMA_VERSION 1 → 2`.

**Schema-version impact** (mirrors ADR-005 §2.6):

| Record | Change | Version |
|---|---|---|
| `RunRequestedEvent` | nested `TestCaseSnapshotItem` gains `repoTest` | `4 → 5` |
| `RunCompletedEvent` | nested `CaseResultSummary` gains `repositoryItems` + `repositoryProvenance` | `4 → 5` |
| `ResultChunkEvent` | gains `repositoryItems` + `repositoryProvenance` | `1 → 2` |
| `RunStartedEvent`, `RunFailedEvent`, `RunCancelRequestedEvent` | unchanged | — |
| `RepoTestSnapshot`, `RepositoryTestItem`, `RepositoryRunProvenance`, `EnvVar`, `SecretEnvVar`, the 6 enums | brand new | n/a |

**Backward compatibility.** Nothing is renamed, moved, or retyped. `FAIL_ON_UNKNOWN_PROPERTIES=false` ⇒ v1–v4 JSON deserialises under v5 records with the new fields null/empty. `schemaVersion` stays advisory. The three shared-events tests gain: "a captured v4 `RunRequestedEvent` / `RunCompletedEvent` still deserialises under the v5 records", "`ResultChunkEvent` v2 round-trips", "`RepoTestSnapshot` round-trips".

**Authoritative-fallback invariant (ADR-005 §2.5, upheld):** if every `results.chunk` is lost, the v5 terminal's `CaseResultSummary.repositoryItems` + `repositoryProvenance` still let the API reconstruct `repository_test_item` rows and the `repository_run` telemetry columns. Chunks remain a latency optimisation, never a correctness dependency.

`RepoTestSnapshot` is a plain nested record, **not** added to the `sealed interface RunEvent` — it is a payload, not a lifecycle fact (same reasoning as ADR-005 for `ResultChunkEvent`).

---

### 3. Migrations V22–V25 (`apps/api` only); no worker migration

| File | Adds | `org_id` placement | `SchemaMigrationIT` assertions to add |
|---|---|---|---|
| `V22__create_repository_connection.sql` | `CREATE TABLE repository_connection (id UUID PK DEFAULT gen_random_uuid(), org_id UUID NOT NULL, project_id UUID NOT NULL REFERENCES projects(id), provider VARCHAR(16) NOT NULL CHECK (provider IN ('GITHUB','GITLAB')), host VARCHAR(255) NOT NULL, owner_path VARCHAR(512) NOT NULL, repo_name VARCHAR(255) NOT NULL, default_ref VARCHAR(255) NOT NULL DEFAULT 'main', credential_ref VARCHAR(64) CHECK (credential_ref ~ '^[A-Z0-9_]{1,64}$'), created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)`; `CREATE UNIQUE INDEX ux_repo_conn_identity ON repository_connection (org_id, project_id, provider, host, owner_path, repo_name) WHERE deleted_at IS NULL`; `CREATE INDEX idx_repo_conn_org ON repository_connection (org_id)`; `CREATE INDEX idx_repo_conn_project ON repository_connection (project_id) WHERE deleted_at IS NULL`. **`credential_ref` is the opaque key only — a provider token is NEVER stored.** | `org_id NOT NULL` — new table; every query filters it. `provider` is `VARCHAR + CHECK`, not a PG enum. | `repository_connection` exists, `org_id is_nullable=NO`; `provider data_type = character varying`; the partial-unique + two indexes exist |
| `V23__add_test_cases_repo_test.sql` | `ALTER TABLE test_cases ADD COLUMN IF NOT EXISTS repo_test JSONB NULL;` — nullable, not indexed (never queried by content), **mutually exclusive** with `api_request` (V9) / `browser_test` (V10), enforced in the authoring DTO (`@AssertTrue`, §11 — ADR-005 §4.2 precedent). Exactly the V9/V10 shape. | n/a — inherits `test_cases.org_id`. | `test_cases` has `repo_test`, `data_type = jsonb`, `is_nullable = YES` |
| `V24__create_repository_run.sql` | `CREATE TABLE repository_run (id UUID PK DEFAULT gen_random_uuid(), org_id UUID NOT NULL, run_id UUID NOT NULL UNIQUE REFERENCES test_runs(id), repository_connection_id UUID NOT NULL REFERENCES repository_connection(id), provider VARCHAR(16) NOT NULL CHECK (provider IN ('GITHUB','GITLAB')), repo_host VARCHAR(255) NOT NULL, repo_path VARCHAR(512) NOT NULL, requested_ref VARCHAR(255) NOT NULL, commit_sha VARCHAR(40) NOT NULL, ref_type VARCHAR(16) NOT NULL CHECK (ref_type IN ('BRANCH','TAG','COMMIT')), framework_preset VARCHAR(16) NOT NULL CHECK (framework_preset IN ('PLAYWRIGHT','JUNIT','PYTEST','CYPRESS','K6')), runner_image_ref VARCHAR(512) NOT NULL, working_dir VARCHAR(512), command_json JSONB NOT NULL, report_format VARCHAR(24) NOT NULL CHECK (report_format IN ('JUNIT_XML','K6_SUMMARY_JSON')), report_paths_json JSONB, artifact_globs_json JSONB, resource_profile VARCHAR(16) NOT NULL CHECK (resource_profile IN ('SMALL','MEDIUM','LARGE')), network_policy VARCHAR(16) NOT NULL CHECK (network_policy IN ('ISOLATED','EGRESS')), timeout_seconds INT NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED')), runner_image_digest VARCHAR(80), container_exit_code INT, items_total INT, items_passed INT, items_failed INT, items_skipped INT, checkout_at TIMESTAMPTZ, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, error_detail VARCHAR(1000), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`; `CREATE INDEX idx_repository_run_org ON repository_run (org_id)`; `CREATE INDEX idx_repository_run_conn ON repository_run (repository_connection_id)`. Columns down to `timeout_seconds` are **frozen at enqueue by the API** (domain rule #2); `state` + `runner_image_digest` … `error_detail` are execution telemetry, filled by the lifecycle + result consumers (API sole writer) from `runs.started` / the v5 terminal / `results.chunk`. | `org_id NOT NULL` — new table; carries `org_id` so the tenant-scoped `GET` and provenance reads need no join. 1:1 with `test_runs` (mirrors `run_queue`). All enum-like columns `VARCHAR + CHECK`. | `repository_run` exists, `org_id is_nullable=NO`, `run_id` unique; `state` / `provider` / `ref_type` / `framework_preset` / `report_format` / `resource_profile` / `network_policy` all `data_type = character varying`; the two indexes exist |
| `V25__create_repository_test_item.sql` | `CREATE TABLE repository_test_item (id UUID PK DEFAULT gen_random_uuid(), org_id UUID NOT NULL, run_id UUID NOT NULL REFERENCES test_runs(id), item_key VARCHAR(64) NOT NULL, suite VARCHAR(1024), name VARCHAR(1024) NOT NULL, status VARCHAR(16) NOT NULL CHECK (status IN ('PASSED','FAILED','SKIPPED','ERROR')), duration_ms INT, failure_type VARCHAR(255), failure_message VARCHAR(8192), attempt_epoch INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (run_id, item_key))`; `CREATE INDEX idx_repo_item_run ON repository_test_item (run_id)`; `CREATE INDEX idx_repo_item_org ON repository_test_item (org_id)`. `item_key = encode(sha256(coalesce(suite,'') || '\x00' || name), 'hex')` — a stable natural key for the epoch-guarded `ON CONFLICT (run_id, item_key)` upsert (ADR-005 §2.4 pattern). `failure_message` is redacted + truncated (`max-item-message-bytes`, 4 KiB) and NULL when `persist-report-snippets = false`. | `org_id NOT NULL` — new table, tenant-scoped. **Kept separate from `test_results`** so `uq_test_results_run_case`, the `test_case_id` FK, and the ADR-008 flaky/trends/slow queries are untouched. | `repository_test_item` exists, `org_id is_nullable=NO`; `status data_type = character varying`; `UNIQUE (run_id, item_key)` present; the two indexes exist |

`flywayHistory_afterMigration_containsVersions1Through21` → **`…Through25`**, `containsExactly("1", …, "25")`. `SchemaMigrationIT` class Javadoc "V1–V21" → "V1–V25". Extend `queueEnums_afterMigration_areNotPgEnumTypes` to also assert `pg_type` has **no** `repository_provider`, `repo_ref_type`, `framework_preset`, `repo_report_format`, `repo_resource_profile`, `repo_network_policy`, `repository_run_state`, `repository_test_item_status`.

**No worker migration.** ADR-003 §3 / ADR-005 §preamble: the Worker's Postgres reach is exactly `worker.execution_attempt`. `worker.execution_attempt.runner_kind` is a free-text `VARCHAR(16)` with no CHECK — `'REPOSITORY'` is a new *value*, not a schema change. Orphan-container recovery uses Docker labels (§9). The worker Flyway stream stays at V1; `SchemaMigrationIT`'s worker-stream check is unchanged.

**No migration for:** the runner-image allowlist (§5, config), report parsing (§7, in-process), the container-hardening settings (§6, `HostConfig`), the Micrometer meters (§12).

---

### 4. `ScmPort` — ref→SHA resolution in the API at enqueue time; provider REST adapters; host allowlist

**Decision: a new `scm` module in `apps/api` (`com.qualityops.api.scm`, hexagonal) owns repository connections and SCM operations. `ScmPort` is an output port with `GitHubScmAdapter` + `GitLabScmAdapter` (JDK `HttpClient`, no new dependency). Ref→SHA resolution runs in the API inside `RepositoryRunPreflight`, invoked by `RunEnqueueService` **before** `test_runs` is inserted (domain rule #2). Provider hosts are checked against a config allowlist and `OutboundAddressGuard` (ADR-008 §3).**

Module name: **`scm`** (source-control management) rather than `repository`, to avoid clashing with the hexagonal "repository" (persistence) term. The entity is `RepositoryConnection`, so its persistence port is `RepositoryConnectionRepository` and its adapter `RepositoryConnectionRepositoryAdapter`.

```java
package com.qualityops.api.scm.application.port.out;

public interface ScmPort {
    RepositoryProvider provider();

    /** "Test connection": repo reachable, credential valid, default branch. Outbound HTTP. */
    ScmProbeResult probe(RepositoryTarget target, String resolvedCredential);

    /** Resolve a mutable branch/tag/short-sha to a full 40-hex commit. */
    ResolvedCommit resolveRef(RepositoryTarget target, String ref, String resolvedCredential)
            throws RepositoryRefUnresolvableException, ScmAuthException;

    /** Mint a short-lived read-only checkout credential where the provider supports
     *  it (GitHub App installation token — Phase 4); otherwise return the PAT
     *  unchanged. */
    CheckoutCredential mintCheckoutCredential(RepositoryTarget target, String resolvedCredential);
}

public record RepositoryTarget(RepositoryProvider provider, String host,
                               String ownerPath, String repoName) {}
public record ResolvedCommit(String sha, RepoRefType refType,
                             java.time.Instant committedAt, String subject) {}
public record ScmProbeResult(boolean ok, String defaultBranch, String resolvedHost,
                             long latencyMs, String error) {}
public record CheckoutCredential(String token, java.time.Instant expiresAt) {}
```

**Adapters** (`adapter/out/scm/`), JDK `HttpClient`, `followRedirects(NEVER)`, connect + request timeout `scm.http-timeout` (PT10S), response body read-capped:

- `GitHubScmAdapter`: `GET https://{apiBase}/repos/{ownerPath}/{repoName}/commits/{ref}` → `.sha`; `GET …/repos/{ownerPath}/{repoName}` → `probe` (`default_branch`); `Authorization: Bearer <token>`. `apiBase` = `api.github.com` for `host == github.com`, else `{host}/api/v3` (enterprise).
- `GitLabScmAdapter`: `GET https://{host}/api/v4/projects/{urlencoded ownerPath/repoName}/repository/commits/{ref}` → `.id`; `GET …/projects/{id}` → `probe`; `PRIVATE-TOKEN: <token>`.

**Host allowlist:** `qualityops.repo-exec.scm.allowed-hosts` (default `github.com,gitlab.com`). A host not on the list ⇒ `RepositoryHostNotAllowedException` → `400`, **no socket opened** (`qualityops.repo.ref_resolve{outcome=host_denied}`). The resolved API host additionally passes `OutboundAddressGuard.check(url, allowHttp=false, allowPrivate=qualityops.repo-exec.scm.allow-private-hosts)` — https-only; loopback / link-local / `169.254.169.254` / CGNAT / ULA / broadcast denied unless the self-hosted-lab escape hatch is set.

**`credentialRef` resolution in the API:** `com.qualityops.api.scm.application.port.out.ScmCredentialResolver` with an `EnvScmCredentialResolver` adapter (env `QUALITYOPS_SCM_CREDENTIAL_<KEY>`, then an optional mounted properties file — mirrors the Worker's `EnvFileSecretResolver`). Azure Key Vault is a Phase-4/5 adapter. The plaintext token is used only for `resolveRef` / `probe` and is **never** stored in `repository_connection`, the `RepoTestSnapshot`, `config_snapshot`, `run_queue.requested_event_json`, `repository_run`, a log line, a report, or an artifact.

**`RepositoryRunPreflight`** (`scm` module, input port `ResolveRepositoryRunUseCase`, implemented by `RepositoryRunPreflightService`) — called by `RunEnqueueService.enqueue` for each `repoTest` snapshot case, *before* the run row exists:

1. Load `repository_connection` by `id` + `org_id` (and its `project_id` must equal the run's project) → `404 REPOSITORY_CONNECTION_NOT_FOUND` if absent/foreign.
2. Resolve `credentialRef` → token (`ScmCredentialResolver`); missing ⇒ `ScmCredentialUnresolvedException` → `400`.
3. Host allowlist + `OutboundAddressGuard` check → `400` / `RepositoryHostNotAllowedException`.
4. `ScmPort.resolveRef(target, ref, token)` → `ResolvedCommit`; not found ⇒ `RepositoryRefUnresolvableException` → `422 REPOSITORY_REF_UNRESOLVABLE`; provider `401`/`403` ⇒ `ScmAuthException` → `400`.
5. Freeze the `RepoTestSnapshot` with `commitSha` + `refType` + `runnerImageRef` (the API reads the same `qualityops.repo-exec.images.<preset>` map, §5), and stage the `repository_run` frozen-columns insert row.

If any step throws, `RunEnqueueService`'s `@Transactional` rolls back — **no `test_runs`, no `run_queue`, no `repository_run`, no orphan run.** This is the immutability guarantee: the snapshot in `config_snapshot` and `requested_event_json` carries a resolved 40-hex SHA and a digest-pinned image ref. A **retry** (ADR-007 — byte-identical `config_snapshot`, no re-freeze) re-runs that exact commit. A **schedule fire** (ADR-006 — a fresh `enqueue`) re-runs preflight and picks up a moved branch.

Cross-module dependency: `execution` (`RunEnqueueService`) → `scm` (`ResolveRepositoryRunUseCase` input port), same shape as its existing dependencies on `project` / `testsuite` / `environment` use-case ports. `scm` must not depend back on `execution`.

---

### 5. Runner-image allowlist — digest-pinned map per framework preset; no user images

**Decision: a config map `qualityops.repo-exec.images.<preset>` of digest-pinned refs, one per `FrameworkPreset`, plus `images.checkout`. There is NO image field on `RepoTestSnapshot` or the authoring DTO in 2F. The API freezes the ref into `repository_run.runner_image_ref` at enqueue; the Worker enforces the same map at container-create time and records the digest actually pulled.**

```
qualityops.repo-exec.images.playwright = mcr.microsoft.com/playwright:v1.59.1-jammy@sha256:<pin>
qualityops.repo-exec.images.junit      = maven:3.9-eclipse-temurin-21@sha256:<pin>
qualityops.repo-exec.images.pytest     = python:3.12-slim@sha256:<pin>
qualityops.repo-exec.images.cypress    = cypress/included:16.0.0@sha256:<pin>
qualityops.repo-exec.images.k6         = grafana/k6:1.8.1@sha256:<pin>
qualityops.repo-exec.images.checkout   = alpine/git:2.45@sha256:<pin>
```

Enforcement points:
- **(a) API** — `RepositoryRunPreflight` freezes only a value present in its copy of the map into `runner_image_ref`.
- **(b) Worker, pre-create** — `DockerContainerRunner` refuses any `imageRef` not byte-equal to a map value ⇒ case `BLOCKED`, `qualityops.repo.blocked{reason=image_not_allowlisted}`, no container.
- **(c) Worker, post-pull** — if the resolved image digest ≠ the pinned digest ⇒ `BLOCKED{reason=digest_mismatch}`.

`image-pull-on-startup=true` pre-pulls all six digests via an `ApplicationRunner`; container create uses the `docker-java` equivalent of `--pull=never` so a run can never trigger an unexpected pull. Supply chain: digests are version-controlled, `CODEOWNERS`-guarded, and added to the ADR-008 `security-scan` Trivy matrix. A vetted custom-image path is a future ADR.

---

### 6. Container hardening — exact `HostConfig`; two-phase checkout/run; `--network=none` by default

**Decision: two hardened containers per attempt sharing one per-attempt workspace bind mount. Phase 1 (`checkout`): `alpine/git` on the egress network fetches the frozen SHA. Phase 2 (`framework`): the digest-pinned preset image on `ISOLATED` (`NetworkMode.NONE`, default) or `EGRESS`, runs the argv command. Neither container ever gets the Docker socket, `--privileged`, host namespaces, or a data-service route.**

Two phases so `git` and the checkout token never enter the framework image or touch the framework container — and an `ISOLATED` test command can run with **no network at all** even though checkout needed egress.

`ContainerRunSpec` → `docker-java` `CreateContainerCmd` + `HostConfig`, identical for both phases except image / network / command:

| Setting | Value |
|---|---|
| `User` | `qualityops.repo-exec.container.runner-uid:runner-gid` (default `12000:12000`), non-root |
| `CapDrop` | `ALL`; no `CapAdd` |
| `SecurityOpt` | `no-new-privileges:true`; seccomp = Docker default profile (set explicitly); AppArmor default |
| root fs | `HostConfig.withReadonlyRootfs(true)` |
| writable space | `Tmpfs {"/tmp": "rw,noexec,nosuid,size=<tmpfs-mb>m"}` + bind mount `type=bind, src=<workspace-root>/<executionId>/<attemptEpoch>, dst=/workspace, rw` — created fresh, `chown` runner-uid, deleted in `finally` |
| disk quota | `HostConfig.withStorageOpt({"size": "<disk-mb from resource profile>m"})` where the storage driver supports it (overlay2+xfs+pquota) **plus** a Worker-side `du` watchdog (`container.workspace-watchdog` PT5S) that `killContainer`s over `container.max-workspace-mb` |
| memory | `Memory = profile.memoryMb·MiB`, `MemorySwap = Memory` (swap disabled) |
| cpu | `NanoCPUs = profile.cpus·1e9` |
| pids | `PidsLimit = container.pids-limit` (512) |
| ulimits | `nofile` soft/hard `container.nofile-soft`/`-hard` (4096/8192) |
| pid 1 | `HostConfig.withInit(true)` (zombie reaping) |
| network | `NetworkMode.NONE` (`ISOLATED`) or attach only to `qualityops-runner-egress` (`EGRESS`) — a bridge network with **no route to `qualityops-internal`**; the checkout container is always `EGRESS` |
| env | explicit allowlist only: resolved `environmentVars`, resolved `secretVars` (masked everywhere else), `CI=true`, `QUALITYOPS_RUN_ID`, `QUALITYOPS_COMMIT_SHA`. **No** checkout token in env |
| lifetime | not `AutoRemove` (inspect exit + read reports first); unconditional `containerRunner.cleanup(executionId)` in a `finally`; time limit = `min(spec.timeoutSeconds, max-run-timeout PT30M, remaining run wall-clock budget)` → on expiry `killContainer(SIGKILL)` + `removeContainer(force=true, removeVolumes=true)` |
| labels | `com.qualityops.managed=true`, `com.qualityops.execution.id=<executionId>`, `com.qualityops.run.id=<runId>`, `com.qualityops.attempt=<n>`, `com.qualityops.phase=checkout|framework` |
| forbidden | no `Binds` to any host path other than the per-attempt workspace; no `/var/run/docker.sock`; no `Privileged`; no `PidMode`/`IpcMode` host; no `Devices`; no `ExtraHosts` |

**Checkout entrypoint** (platform-controlled, set explicitly on `CreateContainerCmd` — the repo cannot override it; there is no repo Dockerfile in 2F): `git init /workspace && git -C /workspace remote add origin <https url> && GIT_ASKPASS=/askpass git -C /workspace fetch --depth 1 origin <sha> && git -C /workspace checkout --detach <sha>`. `/askpass` is a tiny script that echoes the token from a tmpfs-mounted file; the checkout container exits and the tmpfs dies with it. The framework container mounts the now-populated `/workspace` and never sees `origin` credentials.

**Docker socket access for the Worker.** `DockerContainerRunner` connects via `qualityops.repo-exec.docker.host`. In compose / staging this is `tcp://docker-proxy:2375` — a pinned `tecnativa/docker-socket-proxy` that allowlists only `POST /containers/create|start|wait|kill`, `GET /containers/*/json` + `/logs`, `DELETE /containers/*`, `POST /images/create` (pull), `GET /images/*/json` + `/networks`, and **denies** `/exec`, `/commit`, `/build`, `/volumes`, Swarm. `qualityops.repo-exec.docker.require-proxy=true` fails Worker startup if the endpoint resolves to a raw unix socket. Local `mvn spring-boot:run` may use the raw socket with a loud WARN. Phase 5 (k8s) removes the socket entirely — a namespaced ServiceAccount + Role that can only `create/get/delete` Jobs, behind the same `ContainerRunnerPort`.

**Compose topology change** (`infra/compose/docker-compose.yml`): `networks: { qualityops-internal: { internal: true }, qualityops-runner-egress: {} }`; `postgres` / `redis` / `kafka` / `minio` move to `qualityops-internal`; `api` / `worker` / `gateway` join `qualityops-internal` (+ the default ingress net); a new pinned `docker-proxy` service (`/var/run/docker.sock` bind, `CONTAINERS=1 IMAGES=1 NETWORKS=1 POST=1 EXEC=0 VOLUMES=0 BUILD=0`); the `worker` gains `DOCKER_HOST=tcp://docker-proxy:2375`, `REPO_EXEC_REQUIRE_PROXY=true`, `depends_on: docker-proxy`. Runner containers are created by the Worker on `NONE` or `qualityops-runner-egress` and **never** on `qualityops-internal`.

---

### 7. Report parsing — one JUnit-XML parser covers four frameworks; k6 gets a summary-JSON parser; items land in `repository_test_item`

**Decision: `RepoReportFormat ∈ {JUNIT_XML, K6_SUMMARY_JSON}`. `JUnitXmlReportParser` handles Playwright (`--reporter=junit`), JUnit/Surefire (`target/surefire-reports/*.xml`), pytest (`--junitxml`), and Cypress (`mocha-junit-reporter` / `cypress-multi-reporters`). `K6SummaryReportParser` reads `k6 run --summary-export=summary.json`. Parsed items go to the new `repository_test_item` table; `test_results` keeps ONE run-level row for the repo case.**

- `application/port/out/ReportParser` — `RepoReportFormat format();` and `List<RepositoryTestItem> parse(List<java.nio.file.Path> files) throws ReportParseException;`. `ReportParserRegistry` maps a format to its parser. Adapters in `adapter/out/runner/report/`.
- `JUnitXmlReportParser` — `<testsuites>/<testsuite>/<testcase name classname time>`, `<failure message type>`, `<error>`, `<skipped>` → `RepositoryTestItem` (`suite` = `classname` / describe path, `name` = `name`, `status` from the child element, `durationMillis` from `time`, `failureType`/`failureMessage` redacted + truncated). Namespaces, nested `<testsuites>`, and a `<testcase>` with both `<failure>` and `<system-out>` (keep only the redacted `<failure>`) are handled.
- `K6SummaryReportParser` — one synthetic `RepositoryTestItem` per `check` (`PASSED`/`FAILED` from `passes`/`fails`) and per `threshold` (`PASSED`/`FAILED`). Run-level PASS/FAIL comes from the k6 **exit code** (non-zero on a breached threshold) — exact; the item breakdown is best-effort and documented as lower fidelity.
- **File access** — the Worker reads files from `/workspace/<reportPaths glob>` on the bind-mounted host directory (no `docker cp`). `WorkspacePathResolver` resolves each glob match, `toRealPath()` (follows symlinks), and rejects anything not under the workspace root — the zip-slip / path-traversal guard (`qualityops.repo.blocked{reason=spec_invalid}` or a logged parse-skip). Caps: `max-report-bytes` (20 MiB total), an item-count cap, `max-item-message-bytes` per message. A malformed report ⇒ `ReportParseException` ⇒ the run case is `ERROR` with a safe reason; **the run is not aborted**.
- **Run-level result** — one `test_results` row for the single repo `TestCaseSnapshotItem`: `status = PASSED` iff container `exitCode == 0` **and** zero `FAILED`/`ERROR` items, else `FAILED` (or `TIMEOUT`/`ERROR`/`BLOCKED` per the container outcome); `duration_ms` = container wall time; `error_message` = redacted summary (`"3 of 240 tests failed; exit 1"`). ADR-008 flaky/trends/slow analytics over `test_results` keep working unchanged.
- **Per-test items** — `repositoryItems` on `results.chunk` and the v5 terminal drive an org- + `executionId`-guarded, epoch-monotone `INSERT … ON CONFLICT (run_id, item_key) DO UPDATE … WHERE repository_test_item.attempt_epoch <= EXCLUDED.attempt_epoch` (ADR-005 §2.4 pattern) via `RepositoryTestItemRepository`. `RepositoryRunProvenance` drives the `repository_run` telemetry columns under the same guard. A lost chunk is reconciled by the terminal.

---

### 8. Secrets & credentials — resolved at execution time, masked everywhere, gated artifacts

**Decision: `secretVars` and `credentialRef` on `RepoTestSnapshot` are opaque keys (`[A-Z0-9_]{1,64}`), resolved by the **Worker** at execution time via the existing `SecretResolver` (extended `EnvFileSecretResolver`). Plaintext secrets enter only the framework container's env; the checkout token enters only the checkout container's tmpfs. Both are added to a per-execution `Redactor` mask set applied to every stdout line, item message, provenance field, event, and log. Secret-bearing runs gate raw artifact upload (default off).**

- The **API** resolves `credentialRef` (via `ScmCredentialResolver`) only for preflight ref-resolution and the "test connection" probe. The **Worker** resolves the *same* `credentialRef` key (via `SecretResolver`) at execution time for `git fetch`, injects it through `GIT_ASKPASS` from a tmpfs file the platform entrypoint `rm`s before handing over, and never lets it reach an event, `repository_run`, `config_snapshot`, a log, a report, or an artifact. Deployment provisions the key in both the API's and the Worker's secret sources (documented, ADR-005 §4.3 env/file model).
- `secretVars` resolve to framework-container env vars whose **names are author-chosen** (not sensitive). An unresolvable `secretRef` or `credentialRef` ⇒ case `BLOCKED` (`"unresolved secret reference: <KEY>"`, `qualityops.repo.blocked{reason=secret_unresolved}`) — deterministic, never retried (ADR-005 §4.4 precedent: `BLOCKED`, not `ERROR`, because `ERROR` is retryable).
- **Redaction** — for each execution, the resolved secret plaintexts **plus** the checkout token are added to `Redactor` as exact-string masks, on top of the existing bearer / JWT / `AKIA` / PEM / `password=` regexes (`Redactor` gains a per-execution `withLiterals(Set<String>)` builder). Applied to: every streamed stdout/stderr line (logged + staged as a `CONSOLE_LOG` artifact), every `RepositoryTestItem.failureMessage`/`failureType`, `repository_run.error_detail`, and the chunk/terminal `firstFailureReason`.
- **Artifacts** (`ArtifactStoragePort`, ADR-005, reused — `REPORT` + `CONSOLE_LOG` `ArtifactType`s already reserved): report files, console log, and `artifactGlobs` outputs upload best-effort, 10 s-bounded, never fatal (`ArtifactUploadService`). A run that used **any** `secretRef` or a non-null `credentialRef` gates raw artifact upload behind `qualityops.repo-exec.upload-secret-run-artifacts` (**default false** → `ArtifactReference` `UNAVAILABLE:suppressed-secret-run`). Parsed `repository_test_item` rows (status + redacted short message) always flow. `qualityops.repo-exec.persist-report-snippets=false` (default) additionally nulls stored `failure_message`.
- **Short-lived checkout credentials** — `ScmPort.mintCheckoutCredential` exists now and returns the PAT unchanged in 2F; the GitHub App installation-token implementation (`contents:read`, ~1 h TTL) is the Phase-4 hardening, with no call-site change. GitLab uses a scoped project access token now, a job-token-style credential later.

---

### 9. Idempotency & duplicate delivery — the existing claim ledger + Docker labels

**Decision: reuse `worker.execution_attempt` (ADR-003 §3) for "one container per delivery". Add a deterministic container name + a label-based startup/periodic sweep for crash recovery. No new dedup key, no worker table.**

- `RunExecutionService.processRunRequested` already calls `store.claim(executionId, runId, orgId, kind)` before any work: `AlreadyCompleted` ⇒ `publisher.republishTerminal(...)` and stop; `AlreadyRunning` under a live lease ⇒ skip. A redelivered repository `runs.requested` therefore **never launches a second container**. `runner_kind` records `'REPOSITORY'`.
- Belt-and-braces in `RepositoryExecutionRunner`: deterministic container name `qualityops-run-<executionId>-<attemptEpoch>-<phase>`; `createContainer` on a duplicate name → `409` → the runner adopts the existing container (`waitContainer`) if it is running, else force-removes and recreates. This covers a crash between `createContainer` and the first claim heartbeat.
- **Restart-safe reconciliation** — `RepoContainerSweeper` (`@Scheduled(fixedDelayString = "${qualityops.repo-exec.container-sweep-interval:PT10M}")` + an `ApplicationRunner` on boot) calls `ContainerRunnerPort.sweepOrphans(liveExecutionIds)`: for every `label=com.qualityops.managed=true` container, look up the `executionId` label — force-remove (and delete the workspace dir) if `worker.execution_attempt` shows the attempt COMPLETED, or the container is older than the run wall-clock budget, or the `executionId` is not currently owned by a running attempt. This keeps the Worker's Postgres reach unchanged (§3) — the sweeper reads `worker.execution_attempt`, the one table it may.
- **In-run retry (ADR-005 §3)** — a repository case that ends `TIMEOUT`/`ERROR` with `SideEffectClass.NONE_OBSERVED` — image pull failed, checkout failed on a transient network error, the framework container never `exec`'d the argv command — is retried inline within the run budget. Once the framework command has started (`git checkout` succeeded and the argv `exec`'d), `SideEffectClass.POSSIBLE` ⇒ no in-run retry (the test command may have hit external systems). **Queue-driven retry (ADR-007)** still applies to a whole-run `runs.failed`.

---

### 10. Cancellation — the unchanged `runs.cancel` path kills the container

**Decision: reuse `RunCancellationService` + `runs.cancel` + `RunCancelRequestedEvent` + the Worker `CancellationRegistry` (ADR-006 §5) with zero change. `RepositoryExecutionRunner` runs a parallel watcher that kills the container on a cooperative cancel.**

- **`QUEUED` cancel** — the fully-guaranteed path, unchanged: `run_queue WHERE queue_state='QUEUED'` → `CANCELLED` in `run_queue` and `test_runs` (one `TransactionTemplate` unit), **no Kafka, no Worker, no container** (`200`).
- **`DISPATCHED`/`RUNNING` cancel** — cooperative (`202`): the guarded `cancel_requested=true` UPDATE commits, then `RunCancelRequestedEvent` is published on `runs.cancel`; the Worker's `RunCancelConsumer` (group `worker-execution`) records the `executionId` in the bounded in-memory `CancellationRegistry`.
  - **Pre-start** (the registry already has the `executionId` when `processRunRequested` runs) ⇒ claim + `runs.failed("execution cancelled before start")` — **no container** (existing code path in `RunExecutionService`).
  - **Mid-run** — a repo run is one long case, so between-cases polling does not help. `RepositoryExecutionRunner` runs `ContainerRunnerPort.run(...)` on a virtual thread and, in parallel, a watcher loop polls `CancellationToken.isCancelled()` every `qualityops.repo-exec.cancel-poll` (PT2S). On cancel ⇒ `killContainer(name, "SIGTERM")`, wait `qualityops.repo-exec.hard-kill-grace` (PT5S), `killContainer(name, "SIGKILL")`, `containerRunner.cleanup(executionId)`. The case result is `ERROR "run cancelled"` (`SideEffectClass.NONE_OBSERVED`); the run still publishes `runs.completed` with aggregate `FAILED` (ADR-006 §5.4 semantics — a mid-run cancel completes the run, aggregate `FAILED`; `CANCELLED` on `test_runs` is reserved for never-executed runs).

---

### 11. Frontend & API surface — additive; connections are a first-class resource, specs reuse the case endpoints

**Decision: the new `scm` module exposes repository-connection CRUD + a "test connection" action. Repo test specs are authored through the **existing** `POST/PUT /api/v1/suites/{suiteId}/cases` (a new `repoTest` payload, mutually exclusive with `apiRequest`/`browserTest`) and run through the **existing** suite "Run now" (`POST /api/v1/runs`), CI (`POST /api/v1/ci/runs`), and schedule (`POST /api/v1/projects/{projectId}/schedules`) flows — a repo case is enqueued exactly like any other. A new React feature module `apps/web/src/features/repositories/`.**

**Deferred to a later increment (2F scope decision, 2026-09-04):** standalone / ad-hoc execution directly from a repository connection (a `POST /api/v1/projects/{projectId}/repository-runs` "Run now without a suite" sugar endpoint). `test_runs.suite_id` is `NOT NULL FK` (V6) and 2F takes no `test_runs` migration; rather than introduce a hidden system suite or a nullable `suite_id`, ad-hoc repo runs wait for a future increment. Phase 2F fully delivers repository execution through the suite-authored path.

| Method + path | Purpose | RBAC | Notes |
|---|---|---|---|
| `POST /api/v1/projects/{projectId}/repository-connections` | Register a repo connection | `OWNER,ADMIN` | body `{provider, host?, ownerPath, repoName, defaultRef, credentialRef}`; `credentialRef` is the opaque key (an env-var name, not sensitive); `201`; `@Audited(action="scm.connection.create", targetType="repository_connection")` |
| `GET /api/v1/projects/{projectId}/repository-connections` | List (org-scoped) | `OWNER,ADMIN,MEMBER,VIEWER` | never returns a token; shows `credentialRef` |
| `GET /api/v1/repository-connections/{id}` | Get | `OWNER,ADMIN,MEMBER,VIEWER` | flat path (environments precedent); `404` cross-org |
| `PUT /api/v1/repository-connections/{id}` | Update | `OWNER,ADMIN` | `@Audited(action="scm.connection.update")` |
| `DELETE /api/v1/repository-connections/{id}` | Soft delete (`deleted_at`) | `OWNER,ADMIN` | `409 CONNECTION_IN_USE` if a non-deleted `test_cases.repo_test` references it; `@Audited(action="scm.connection.delete")` |
| `POST /api/v1/repository-connections/{id}/test` | Test connection (outbound probe) | `OWNER,ADMIN,MEMBER` | `@RateLimited(operation="scm.test-connection", limit="${qualityops.ratelimit.scm-test.limit:30}", window="PT1H")`; `@Audited(action="scm.connection.test")`; → `{ok, defaultBranch, resolvedHost, latencyMs, error?}` |
| _(deferred)_ `POST /api/v1/projects/{projectId}/repository-runs` | Ad-hoc "Run now" without a suite | — | **Not in 2F** — see the deferral note above; repo cases run through the suite-authored path |
| `POST /api/v1/suites/{suiteId}/cases`, `PUT /api/v1/cases/{id}` | Author a repo test case | existing RBAC | `CreateTestCaseRequest` / `UpdateTestCaseRequest` gain an optional `repoTest` (`RepoTestPayload`); `@AssertTrue` "at most one of {apiRequest, browserTest, repoTest}" |
| `POST /api/v1/runs`, `POST /api/v1/ci/runs`, `POST /api/v1/projects/{projectId}/schedules` | Trigger / CI-trigger / schedule a suite containing a repo case | existing | **unchanged** — a repo case enqueues through `EnqueueRunUseCase` exactly like any other |

`GET /api/v1/runs/{id}` and `GET /api/v1/runs/{id}/results` gain an **additive-nullable** `repositoryRun` block (`{provider, repoPath, commitSha, refType, framework, runnerImageDigest, containerExitCode, itemsTotal, itemsPassed, itemsFailed, itemsSkipped, checkoutAt, startedAt, finishedAt}`) and, on the results payload, a `repositoryItems` array from `repository_test_item`. `GlobalExceptionHandler` gains handlers for `RepositoryRefUnresolvableException` (`422 REPOSITORY_REF_UNRESOLVABLE`), `RepositoryHostNotAllowedException` (`400`), `ScmAuthException` (`400`), `RepositoryConnectionInUseException` (`409 CONNECTION_IN_USE`).

**React** (`apps/web/src/features/repositories/`): `RepositoryConnectionsPage` (list / create / test / delete), `RepositoryConnectionForm`, a third "Repository" tab in the test-case editor (`RepoTestForm`), a "Repository execution" panel + a "Test items" table on the run-detail page; TanStack Query hooks `useRepositoryConnections`, `useTestRepositoryConnection`. Named exports, functional components, strict TS. All additive. (No "Run now from a connection" modal in 2F — a repo case is run via the existing suite "Run now" / schedule UI.)

---

### 12. Config, meters, `.env`, compose

**New config properties** (`qualityops.repo-exec.*`; the API binds `scm.*`, `images.*`, timeouts, `default-resource-profile`; the Worker binds the rest):

```
# --- API + Worker ---
qualityops.repo-exec.enabled                         true
qualityops.repo-exec.images.playwright               mcr.microsoft.com/playwright:v1.59.1-jammy@sha256:<pin>
qualityops.repo-exec.images.junit                    maven:3.9-eclipse-temurin-21@sha256:<pin>
qualityops.repo-exec.images.pytest                   python:3.12-slim@sha256:<pin>
qualityops.repo-exec.images.cypress                  cypress/included:16.0.0@sha256:<pin>
qualityops.repo-exec.images.k6                       grafana/k6:1.8.1@sha256:<pin>
qualityops.repo-exec.default-run-timeout             PT10M
qualityops.repo-exec.max-run-timeout                 PT30M
qualityops.repo-exec.default-resource-profile        SMALL

# --- API only ---
qualityops.repo-exec.scm.allowed-hosts               github.com,gitlab.com
qualityops.repo-exec.scm.allow-private-hosts         false
qualityops.repo-exec.scm.http-timeout                PT10S
qualityops.repo-exec.scm.ref-resolve-timeout         PT15S
qualityops.repo-exec.scm.credential-env-prefix       QUALITYOPS_SCM_CREDENTIAL_
qualityops.repo-exec.scm.credential-file             ${QUALITYOPS_SCM_CREDENTIAL_FILE:}

# --- Worker only ---
qualityops.repo-exec.images.checkout                 alpine/git:2.45@sha256:<pin>
qualityops.repo-exec.image-pull-on-startup           true
qualityops.repo-exec.docker.host                     ${DOCKER_HOST:unix:///var/run/docker.sock}
qualityops.repo-exec.docker.require-proxy            ${REPO_EXEC_REQUIRE_PROXY:false}
qualityops.repo-exec.container.runner-uid            12000
qualityops.repo-exec.container.runner-gid            12000
qualityops.repo-exec.container.pids-limit            512
qualityops.repo-exec.container.tmpfs-mb              256
qualityops.repo-exec.container.max-workspace-mb      2048
qualityops.repo-exec.container.workspace-watchdog    PT5S
qualityops.repo-exec.container.nofile-soft           4096
qualityops.repo-exec.container.nofile-hard           8192
qualityops.repo-exec.network.isolated-mode          none
qualityops.repo-exec.network.egress-network         qualityops-runner-egress
qualityops.repo-exec.resource-profiles.small.cpus         1
qualityops.repo-exec.resource-profiles.small.memory-mb    1024
qualityops.repo-exec.resource-profiles.medium.cpus        2
qualityops.repo-exec.resource-profiles.medium.memory-mb   2048
qualityops.repo-exec.resource-profiles.large.cpus         4
qualityops.repo-exec.resource-profiles.large.memory-mb    4096
qualityops.repo-exec.workspace-root                 ${java.io.tmpdir}/qualityops-repo-workspace
qualityops.repo-exec.max-report-bytes               20971520
qualityops.repo-exec.max-item-message-bytes         4096
qualityops.repo-exec.persist-report-snippets        false
qualityops.repo-exec.upload-secret-run-artifacts    false
qualityops.repo-exec.cancel-poll                    PT2S
qualityops.repo-exec.hard-kill-grace               PT5S
qualityops.repo-exec.container-sweep-interval       PT10M
qualityops.repo-exec.secret-env-prefix              QUALITYOPS_SECRET_
```

Bound by `RepoExecApiProperties` (`apps/api`, `com.qualityops.api.config`) and `RepoExecWorkerProperties` (`apps/worker`, `com.qualityops.worker.config`). The Worker's `spring.task.scheduling.pool.size` gains room for `RepoContainerSweeper` (bump if currently default) alongside the existing `AttemptRetentionSweeper` / `BrowserArtifactSweeper` / `ArtifactStagingSweeper`.

**`.env.example`** — `QUALITYOPS_REPO_EXEC_ENABLED`, `DOCKER_HOST`, `REPO_EXEC_REQUIRE_PROXY`, `QUALITYOPS_SCM_CREDENTIAL_<KEY>` (example), `QUALITYOPS_SCM_CREDENTIAL_FILE`.

**Compose** — see §6 (base `docker-compose.yml`: `qualityops-internal` `internal: true`, `qualityops-runner-egress`, `docker-proxy`). `docker-compose.dev.yml`: `worker`/`api` gain `QUALITYOPS_REPO_EXEC_ENABLED=true`, `worker` gets `DOCKER_HOST=tcp://docker-proxy:2375` + `REPO_EXEC_REQUIRE_PROXY=true` + `depends_on: docker-proxy`, plus a fixture `QUALITYOPS_SCM_CREDENTIAL_DEMO`. `infra/docker/Dockerfile.worker`: no change beyond the new Maven dependency (runner images are pulled at runtime, not baked).

**New Micrometer meters** (Prometheus surface; bounded cardinality, no `org` tag; pre-registered à la `QueueMetrics` so a scrape sees them at 0):

| Meter | Type | Tags |
|---|---|---|
| `qualityops.repo.ref_resolve` | timer | `provider ∈ {GITHUB,GITLAB}`, `outcome ∈ {resolved, not_found, auth_failed, host_denied, error}` (recorded in the API) |
| `qualityops.repo.image_pull` | timer | `preset`, `outcome ∈ {ok, error}` |
| `qualityops.repo.container_duration` | timer | `preset`, `phase ∈ {checkout, framework}` |
| `qualityops.repo.runs` | counter | `preset`, `outcome ∈ {passed, failed, timeout, error, blocked}` |
| `qualityops.repo.container_kills` | counter | `reason ∈ {timeout, cancel, workspace_quota, sweep}` |
| `qualityops.repo.report_parse` | timer | `format ∈ {JUNIT_XML, K6_SUMMARY_JSON}`, `outcome ∈ {ok, error}` |
| `qualityops.repo.items` | counter | `status ∈ {passed, failed, skipped, error}` |
| `qualityops.repo.blocked` | counter | `reason ∈ {host_denied, image_not_allowlisted, digest_mismatch, secret_unresolved, spec_invalid, worker_unavailable}` |
| `qualityops.repo.orphans_swept` | counter | — |

---

### 13. Observability & provenance record

Every repository run records, in `repository_run` (API-written) and on `qualityops.repo.*` meters: the **exact resolved commit SHA**, the **requested ref + ref type**, the **runner image ref frozen at enqueue** and the **image digest actually pulled**, the framework preset, the report format, the resource profile, the network policy, the timeout, the container **exit code**, `checkout_at` / `started_at` / `finished_at`, the item counts, and a redacted `error_detail`. `repository_test_item` records the normalized per-test breakdown. `state` is derived by the API: `PENDING` at enqueue, `RUNNING` on `runs.started`, `COMPLETED`/`FAILED` on the terminal (matched on `org_id` + `executionId` — a stale/foreign event is a 0-row no-op), `CANCELLED` on a `QUEUED`-phase cancel. The `realtime` module's `RunProgressEvent` (ADR-008 §5) carries the run-level status transitions unchanged — no per-item streaming in 2F (a future additive option via a `SCHEMA_VERSION` bump).

---

## Consolidated summary

### New migrations V22–V25 (append-only, `apps/api` only, in order)

| File | Purpose | `org_id` placement | `SchemaMigrationIT` assertions to add |
|---|---|---|---|
| `V22__create_repository_connection.sql` | `repository_connection` — org- + project-scoped GitHub/GitLab connection; `provider VARCHAR + CHECK`; `credential_ref` opaque key only (regex CHECK); `deleted_at` soft delete; partial unique on the canonical identity `WHERE deleted_at IS NULL`; `idx_repo_conn_org`, `idx_repo_conn_project WHERE deleted_at IS NULL` | `org_id NOT NULL` — new table | table + `org_id is_nullable=NO`; `provider` `character varying`; partial-unique + 2 indexes exist |
| `V23__add_test_cases_repo_test.sql` | `ALTER TABLE test_cases ADD COLUMN repo_test JSONB NULL` — nullable, unindexed, mutually exclusive with `api_request`/`browser_test` (V9/V10 precedent) | n/a — inherits `test_cases.org_id` | `test_cases.repo_test` `jsonb`, `is_nullable=YES` |
| `V24__create_repository_run.sql` | `repository_run` — 1:1 with `test_runs` (`run_id UNIQUE`); frozen spec columns (resolved `commit_sha`, `ref_type`, `framework_preset`, digest-pinned `runner_image_ref`, `command_json`, `resource_profile`, `network_policy`, `timeout_seconds`) + execution-telemetry columns (`state`, `runner_image_digest`, `container_exit_code`, `items_*`, `checkout_at`/`started_at`/`finished_at`, `error_detail`); 8 `VARCHAR + CHECK` enums; `idx_repository_run_org`, `idx_repository_run_conn` | `org_id NOT NULL` — new table; 1:1 with `test_runs` (mirrors `run_queue`) | table + `org_id is_nullable=NO` + `run_id` unique; `state`/`provider`/`ref_type`/`framework_preset`/`report_format`/`resource_profile`/`network_policy` all `character varying` |
| `V25__create_repository_test_item.sql` | `repository_test_item` — normalized per-test results; `item_key` = sha256(suite+name); `UNIQUE (run_id, item_key)` drives the epoch-guarded upsert; `status VARCHAR + CHECK`; `failure_message` redacted/truncated/nullable; `idx_repo_item_run`, `idx_repo_item_org` | `org_id NOT NULL` — new table; kept separate from `test_results` | table + `org_id is_nullable=NO` + `UNIQUE (run_id, item_key)`; `status` `character varying` |

`flywayHistory` version list → `containsExactly("1", …, "25")`; class Javadoc "V1–V21" → "V1–V25"; `queueEnums_afterMigration_areNotPgEnumTypes` extended with the 8 new enum-like names. **No worker migration** (`worker` stream stays V1; `runner_kind='REPOSITORY'` is a value, not a schema change).

**No migration for:** the runner-image allowlist (§5, config), container hardening (§6, `HostConfig`), report parsing (§7, in-process), the Micrometer meters (§12).

### Event schema changes

| Record | Version | Change |
|---|---|---|
| `RunRequestedEvent` | `4 → 5` | nested `TestCaseSnapshotItem` gains `repoTest` (nullable) |
| `RunCompletedEvent` | `4 → 5` | nested `CaseResultSummary` gains `repositoryItems` (list) + `repositoryProvenance` (nullable) |
| `ResultChunkEvent` | `1 → 2` | gains `repositoryItems` + `repositoryProvenance` |
| `RunStartedEvent`, `RunFailedEvent`, `RunCancelRequestedEvent` | — | unchanged |
| new | — | `RepoTestSnapshot`, `RepositoryTestItem`, `RepositoryRunProvenance`, `EnvVar`, `SecretEnvVar`; enums `RepositoryProvider`, `RepoRefType`, `FrameworkPreset`, `RepoReportFormat`, `RepoResourceProfile`, `RepoNetworkPolicy` |

Additive only; `FAIL_ON_UNKNOWN_PROPERTIES=false` + convenience ctors keep v1–v4 JSON and every non-repo call site working; `spring.json.trusted.packages: com.qualityops.*` unchanged. `EventBackwardCompatibilityTest` / `EventContractTest` / `EventSerializationRoundTripTest` stay green with added cases.

### New / changed endpoints

| Method + path | Purpose | RBAC | Notes |
|---|---|---|---|
| `POST /api/v1/projects/{projectId}/repository-connections` | Register a connection | `OWNER,ADMIN` | `201`; `@Audited` |
| `GET /api/v1/projects/{projectId}/repository-connections` | List (org-scoped) | `OWNER,ADMIN,MEMBER,VIEWER` | never returns a token |
| `GET /api/v1/repository-connections/{id}` | Get | `OWNER,ADMIN,MEMBER,VIEWER` | flat path; `404` cross-org |
| `PUT /api/v1/repository-connections/{id}` | Update | `OWNER,ADMIN` | `@Audited` |
| `DELETE /api/v1/repository-connections/{id}` | Soft delete | `OWNER,ADMIN` | `409 CONNECTION_IN_USE`; `@Audited` |
| `POST /api/v1/repository-connections/{id}/test` | Test connection (outbound probe) | `OWNER,ADMIN,MEMBER` | `@RateLimited("scm.test-connection", 30/h)` + `@Audited` |
| `POST/PUT …/cases` | Author a repo test case | existing | new `repoTest` payload; `@AssertTrue` one-of |
| `POST /api/v1/runs`, `POST /api/v1/ci/runs`, `POST …/schedules` | Trigger / schedule a repo-case suite | existing | unchanged |
| `GET /api/v1/runs/{id}`, `…/results` | — | existing | additive-nullable `repositoryRun` + `repositoryItems` blocks |

Additive-only. New error codes: `REPOSITORY_CONNECTION_NOT_FOUND` (`404`), `REPOSITORY_REF_UNRESOLVABLE` (`422`), `CONNECTION_IN_USE` (`409`). `POST /api/v1/repository-connections/{id}/test` and `…/repository-runs` may also return `429 RATE_LIMITED` (standard, not a contract break).

### New config properties (`qualityops.repo-exec.*`, with defaults)

See §12 for the full block. Bound by `RepoExecApiProperties` (`apps/api`) + `RepoExecWorkerProperties` (`apps/worker`). Worker `spring.task.scheduling.pool.size` bumped for `RepoContainerSweeper`. `.env.example` gains `QUALITYOPS_REPO_EXEC_ENABLED`, `DOCKER_HOST`, `REPO_EXEC_REQUIRE_PROXY`, `QUALITYOPS_SCM_CREDENTIAL_<KEY>`, `QUALITYOPS_SCM_CREDENTIAL_FILE`. `docker-compose.yml` gains the `qualityops-internal` (`internal: true`) + `qualityops-runner-egress` networks and the `docker-proxy` service.

### New Micrometer meters (bounded cardinality, no `org` tag)

| Meter | Type | Tags |
|---|---|---|
| `qualityops.repo.ref_resolve` | timer | `provider`, `outcome ∈ {resolved, not_found, auth_failed, host_denied, error}` |
| `qualityops.repo.image_pull` | timer | `preset`, `outcome ∈ {ok, error}` |
| `qualityops.repo.container_duration` | timer | `preset`, `phase ∈ {checkout, framework}` |
| `qualityops.repo.runs` | counter | `preset`, `outcome ∈ {passed, failed, timeout, error, blocked}` |
| `qualityops.repo.container_kills` | counter | `reason ∈ {timeout, cancel, workspace_quota, sweep}` |
| `qualityops.repo.report_parse` | timer | `format`, `outcome ∈ {ok, error}` |
| `qualityops.repo.items` | counter | `status ∈ {passed, failed, skipped, error}` |
| `qualityops.repo.blocked` | counter | `reason ∈ {host_denied, image_not_allowlisted, digest_mismatch, secret_unresolved, spec_invalid, worker_unavailable}` |
| `qualityops.repo.orphans_swept` | counter | — |

### New dependencies

| Dependency | Scope | Why | Alternatives rejected |
|---|---|---|---|
| `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5` | `apps/worker` runtime | Typed Docker Remote API client for `ContainerRunnerPort` — create / start / wait / logs / kill / rm with structured errors and bounded I/O. Already transitively present via Testcontainers, so the version and transport are proven. Cleanly swappable — the port is the Phase-5 seam. | *Testcontainers `GenericContainer` in prod* (a test framework; Ryuk resource-reaper sidecar; `test`-scoped everywhere here). *`docker` CLI via `ProcessBuilder`* (fragile arg quoting, weak error surface, harder to bound, needs the CLI in the image). *Kubernetes client now* (no k8s until Phase 5). *spotify-docker-client* (unmaintained). |
| `tecnativa/docker-socket-proxy` image | infra (compose / staging) | Verb-allowlisted broker in front of `/var/run/docker.sock` so the Worker cannot `exec` / `commit` / `build` or bind arbitrary host paths. `require-proxy=true` in staging. | *Raw socket* (host root; allowed only for local `mvn spring-boot:run` with a WARN). *Rootless Docker / Sysbox* (heavier lab setup; Phase-5 k8s removes the socket entirely via RBAC). |
| Allowlisted, digest-pinned runner images (`mcr.microsoft.com/playwright`, `maven`, `python`, `cypress/included`, `grafana/k6`, `alpine/git`) | infra | Frameworks + checkout for repository runs; digest-pinned in config, `CODEOWNERS`-guarded, Trivy-scanned in CI (§5). | *User-provided image* (arbitrary supply chain + entrypoint — a future ADR). |

---

## Alternatives considered

### Runner host

- **A new `apps/runner-orchestrator` Spring Boot app** consuming a `runs.repo.requested` topic and driving Docker. Rejected: duplicates the Kafka consumer + DLT config, the `worker.execution_attempt` claim ledger (dedup, lease-steal, cached-terminal re-emit), `ArtifactUploadService` + `ArtifactStoragePort`, `EnvFileSecretResolver`, the `CancellationRegistry` + `runs.cancel` consumer, `RunLifecyclePublisher`, `Redactor`, and the per-case retry loop — all directly reusable. The container boundary already provides the isolation a separate deployable would. A same-image `worker` / `worker-repo` deployment split is the cheaper scale-out if blast radius demands it.
- **Reuse the Worker but run the repo command via `ProcessBuilder` under a `chroot` / Linux namespaces from Java.** Rejected: that *is* executing untrusted code in the long-lived Worker process; the JVM has no real sandbox primitive; it violates the hard isolation requirement outright.

### Kafka / events

- **A new topic `runs.repo.requested` + a parallel dispatcher / reaper / retry.** Rejected: forks ADR-006/007's queue admission control, priority aging, tenant concurrency, cancellation, retry, reaper, and webhooks; the plan explicitly says reuse them. `runs.requested` + an additive snapshot is the boring choice.
- **A top-level `RepositoryRunRequestedEvent` record instead of extending `TestCaseSnapshotItem`.** Rejected: the per-case snapshot list + `ExecutionRunnerResolver` is the established extension point — ADR-003/004 added `apiRequest` / `browserTest` the same way. A new event would need its own consumer and would not ride the existing lifecycle / `results.chunk` / queue plumbing.
- **`RepoTestSnapshot` inside the `sealed interface RunEvent`.** Rejected for the same reason ADR-005 kept `ResultChunkEvent` out: it is a payload, not a lifecycle transition, and would force every exhaustive `switch` in both apps to change.

### Storage shape

- **A dedicated `repository_test_spec` table instead of `test_cases.repo_test` JSONB.** Rejected: V9 (`api_request`) / V10 (`browser_test`) set the precedent — an authored, mutually-exclusive per-case spec is a nullable JSONB column on `test_cases`, so it flows through `EnqueueRunUseCase`, snapshotting, and scheduling for free. A table would need its own CRUD, its own snapshotting, and its own lifecycle. `repository_connection` *is* a table — it has a credential, a lifecycle, a "test" action, and is shared across many cases.
- **Repo-run provenance as ~15 nullable columns on `test_runs`.** Rejected: pollutes the core immutable aggregate for a minority run type. A 1:1 `repository_run` table mirrors `run_queue` and keeps ADR-008's `test_runs` analytics untouched.
- **Overload `test_results` with per-framework items.** Rejected: `uq_test_results_run_case`, the `test_case_id` FK, and ADR-008's flaky / trends / slow queries all assume one row per authored case. A new `repository_test_item` table keeps them intact.

### Container mechanics

- **One container that both checks out and runs.** Rejected in favour of a two-phase checkout/run split so `git` and the checkout token never enter the framework image or touch the framework container, and an `ISOLATED` test command can run with `--network=none`.
- **User-provided runner image in 2F.** Rejected: an arbitrary image is an arbitrary supply chain and an arbitrary entrypoint. Digest-pinned allowlist only; a vetted-custom-image path is a future ADR.
- **Raw Docker socket into the Worker.** Accepted only for local `mvn spring-boot:run` with a loud WARN; staging / compose require the `tecnativa/docker-socket-proxy` verb allowlist (`require-proxy=true`); Phase 5 removes the socket entirely (k8s RBAC).
- **Testcontainers `GenericContainer` as the runtime mechanism.** Rejected — it is a test framework (Ryuk sidecar, JUnit lifecycle assumptions, `test` scope everywhere in this repo). `docker` CLI shell-out and a Kubernetes client now were also rejected — see the dependencies table.

### SCM

- **`git clone` in the API or Worker JVM against the untrusted URL.** Rejected: pack-bomb / history-bomb exposure and a token-handling surface in a long-lived process. Ref→SHA is a REST call in the API; the clone is a depth-1 fetch in the isolated checkout container.
- **Resolve the ref in the Worker at execution time.** Rejected: the run (and its immutable `config_snapshot`) must already carry the SHA — resolution must precede `test_runs` insertion (domain rule #2).

### Report parsing

- **A language-specific result tool per framework.** Rejected: JUnit XML is the universal interchange all four non-k6 frameworks emit; one parser covers Playwright / JUnit / pytest / Cypress. k6 (no per-test concept) gets a dedicated small summary-JSON parser; its run-level PASS/FAIL from the exit code is exact.

---

## Risks

- **DNS rebind between ref-resolution and checkout.** The API validates `repoHost` against the allowlist and `OutboundAddressGuard` at enqueue; the checkout container re-resolves at execution and could see a rebind to a private IP. Mitigations: the checkout container is on the egress-only network with **no route to `qualityops-internal`** regardless of resolved IP; `OutboundAddressGuard` re-checks in the Worker; the allowlist is a tiny hostname set. Residual (IP-pinned checkout is a follow-up) — identical posture to ADR-003 §5 / ADR-007 / ADR-008 §3, accepted.
- **Docker socket exposure.** Daemon access = host root. Mitigations: `tecnativa/docker-socket-proxy` verb allowlist (no `/exec`, `/commit`, `/build`, `/volumes`); the Worker code only ever binds the per-attempt workspace path; `require-proxy=true` in staging; runner containers never get the socket; Phase 5 removes it (k8s RBAC). Residual: the Worker process's trust level rises — documented.
- **Image-pull supply chain.** A poisoned upstream tag. Mitigations: digest-pinned refs in version control, `CODEOWNERS`-guarded; `--pull=never` after a startup pre-pull; the ADR-008 Trivy job extended to the six runner images; no user images in 2F.
- **Secret / token leakage via report files or stdout.** A repo test can print a secret or the checkout token into its JUnit `<failure>` message or stdout. Mitigations: a per-execution exact-string mask set (resolved secrets + token) added to `Redactor` for every stdout line, item message, and provenance field; `persist-report-snippets=false` (default) suppresses stored messages; secret-bearing runs gate raw artifact upload (`UNAVAILABLE:suppressed-secret-run`); the token lives only in a checkout-container tmpfs file deleted before the framework container starts. Residual: a secret the author explicitly globs into an artifact with the gate manually opened — same posture as ADR-005 §4.4.
- **Disk exhaustion.** A run that fills `/workspace` or `/tmp`. Mitigations: `--tmpfs` size cap; `StorageOpt size` where the driver supports it (overlay2+xfs+pquota); a `du` watchdog that kills the container over `max-workspace-mb`; unconditional workspace-dir delete in `finally`; the startup + periodic orphan sweep; `max-report-bytes` on read-back. Residual: without pquota the watchdog is the only bound — documented.
- **Zip-slip / path traversal on `reportPaths` / `artifactGlobs`.** A malicious `../../etc/shadow` glob or a `/workspace` symlink pointing outside. Mitigation: `WorkspacePathResolver` resolves every match, `toRealPath()` (follows symlinks), and rejects anything not under the workspace root; the Worker reads files, never extracts archives; count + byte caps.
- **Long-lived-Worker blast radius.** A wedged daemon or a filled host degrades browser / API execution on the same Worker. Mitigations: every limit above; `qualityops.repo-exec.enabled=false` kill switch; the documented `worker` / `worker-repo` deployment split (same image, different enabled kinds).
- **Rolling-deploy skew.** Old Worker + new API: a v5 `runs.requested` carrying `repoTest` reaches a Worker with no `REPOSITORY` runner → the `ExecutionRunnerResolver` sentinel makes the case `BLOCKED "repository execution unavailable"` (`qualityops.repo.blocked{reason=worker_unavailable}`), never an NPE or a simulated run. New Worker + old API: no `repoTest` is ever produced; `RepositoryExecutionRunner` is never selected. Deploy API + Worker together (ADR-002…008 rule).
- **`docker-java` transitive weight / CVEs.** New dependency surface — mitigated by it already being transitively present via Testcontainers, a version pin in the parent `dependencyManagement`, and the ADR-008 `security-scan` job.
- **k6 fidelity.** k6 has no per-test concept; the summary-JSON mapping is coarse (checks / thresholds, not "tests"). Documented; the run-level PASS/FAIL from the exit code is exact, the item breakdown is best-effort.

---

## Testing strategy

Mapping to the PHASE-2-PLAN §2F test list:

| Plan item | Test(s) |
|---|---|
| **ref-to-commit resolution** | `GitHubRefResolverTest`, `GitLabRefResolverTest` (MockWebServer): branch / tag → 40-hex SHA; unknown ref → `RepositoryRefUnresolvableException`; provider `401` → `ScmAuthException`; host off the allowlist → `RepositoryHostNotAllowedException` with **no socket opened**; `qualityops.repo.ref_resolve` outcome tag asserted |
| **snapshot immutability** | `RepositoryRunSnapshotImmutabilityTest` (pure / serialization): enqueue resolves `main`→SHA1; move the fixture branch to SHA2; a **retry** (byte-identical `config_snapshot`, ADR-007) re-runs SHA1; a **fresh enqueue** (schedule fire) resolves SHA2. `RepositoryRunEnqueueIT` (Testcontainers): an unresolvable ref ⇒ `422` and **no `test_runs` / `run_queue` / `repository_run` row**; a resolvable ref ⇒ a frozen `repository_run` row with a 40-hex `commit_sha` and a digest-pinned `runner_image_ref` before `runs.requested` is ever published |
| **runner selection** | `ExecutionRunnerResolverTest`: `repoTest` present ⇒ `REPOSITORY` even alongside `apiRequest` / `browserTest` (precedence `repoTest > browserTest > apiRequest > simulated`); `resolvedKindFor` picks `REPOSITORY` if any repo case; an unregistered `REPOSITORY` runner ⇒ the `BLOCKED` sentinel, no NPE |
| **report parsing** | `JUnitXmlReportParserTest` — Surefire, pytest `--junitxml`, Playwright `--reporter=junit`, and Cypress `mocha-junit-reporter` sample XML fixtures → correct `RepositoryTestItem` lists (status, duration, failure message / type, nested `<testsuites>`, `<skipped>`, `<error>`); malformed XML → `ReportParseException` → case `ERROR` with a safe reason, run not aborted. `K6SummaryReportParserTest` — a `summary.json` fixture → checks / thresholds items; missing file → `ERROR` |
| **redaction** | `RepoRedactionTest`: the resolved secret plaintext **and** the checkout token added to the per-execution `Redactor` mask set ⇒ scrubbed from stdout lines, `RepositoryTestItem.failureMessage` / `failureType`, `repository_run.error_detail`, and the chunk / terminal `firstFailureReason`; `persist-report-snippets=false` ⇒ stored `failure_message` NULL |
| **path validation** | `WorkspacePathResolverTest`: a `../` escape, an absolute path, and a symlink whose target is outside the root are all rejected; a `**` glob stays within the root; a resolved report path outside the root ⇒ `qualityops.repo.blocked{reason=spec_invalid}` (or a logged parse-skip) |
| **cancellation** | `RepositoryCancellationTest`: `CancellationToken` flips mid-`waitContainer` ⇒ SIGTERM → (grace) → SIGKILL, `ContainerRunnerPort.cleanup(executionId)` invoked, case `ERROR "run cancelled"`, run still publishes `runs.completed` aggregate `FAILED`; a pre-start cancel (registry pre-seeded) ⇒ `runs.failed("execution cancelled before start")`, **no container**. `RunCancellationIT` extended: a `QUEUED` repo run cancel ⇒ `CANCELLED` in both tables, no Kafka, no container |
| **Testcontainers/Docker clone-and-run IT** | `RepositoryExecutionIT` (`@Tag("docker")`, hermetic — a bare fixture repo served by a pinned `nginx` + `git` container on a test network, offline-friendly): enqueue at a fixed SHA → checkout container fetches depth-1 → framework container (`python:3.12-slim` running `pytest --junitxml` on a 3-test fixture) → parse (2 pass, 1 fail) → one `test_results` row (`FAILED`) + 3 `repository_test_item` rows + a `repository_run` provenance row (exit code, digest, SHA) → an artifact uploaded to `MinIOContainer` → the workspace dir and both containers are gone |
| **duplicate delivery → one container** | `DuplicateDeliveryLaunchesOneContainerIT` (`@EmbeddedKafka` + Testcontainers Postgres + Docker): deliver the same repo `runs.requested` twice ⇒ the `worker.execution_attempt` claim ⇒ exactly one framework container created (assert via a `ContainerRunnerPort` spy / a `docker ps -a` label count == 1), one terminal, `repository_test_item` rows not duplicated (epoch-guarded upsert); a Worker restart mid-run reconciles via the label sweep and does not spawn a second container |
| **no data-service reach** | `RunnerCannotReachDataServicesIT` (`@Tag("docker")`): the fixture command tries to connect to `postgres:5432`, `redis:6379`, and the MinIO endpoint; all fail (`NetworkMode.NONE` / egress-only with no `qualityops-internal` route); the run still completes and the fixture's JUnit report records every probe as refused |
| **no unapproved image** | `UnapprovedImageIsRejectedIT`: an injected non-allowlisted `imageRef` and, separately, a digest mismatch ⇒ case `BLOCKED`, `qualityops.repo.blocked{reason=image_not_allowlisted|digest_mismatch}`, no container created |
| **resource limits enforced** | `ResourceLimitsEnforcedIT`: a `malloc`-past-cap fixture ⇒ OOM-kill, exit 137; a fork-bomb ⇒ `PidsLimit` stops it; a `sleep` past the timeout ⇒ `TIMEOUT` + SIGKILL path; a `>max-workspace-mb` writer ⇒ the watchdog kills it; every container is removed afterward |
| **no workspace escape** | `WorkspaceEscapeIsContainedIT`: writing `/etc/passwd` ⇒ `EROFS` (read-only rootfs); writing outside `/workspace` and `/tmp` ⇒ denied; a `reportPaths` glob `../../../../etc/hostname` ⇒ `WorkspacePathResolver` rejects it; no file is read outside the workspace root |
| **no secret leak** | `SecretNotLeakedIT` (`@Tag("docker")` + `MinIOContainer`): a `secretRef` env var plus a checkout token; the fixture echoes `$LOGIN_PASSWORD` to stdout and into its JUnit `<failure>` message. Assert the staged `CONSOLE_LOG` artifact, `repository_test_item.failure_message`, the `results.chunk`, the v5 terminal, `repository_run.error_detail`, and every Worker log line have it masked; the checkout token appears nowhere; with `upload-secret-run-artifacts=false` the raw report artifact is `UNAVAILABLE:suppressed-secret-run` |
| **UI E2E** | `apps/web` Playwright `repository-run.spec.ts`: connect a fixture repo → "Test connection" turns green → configure framework + command → **Run now** → observe `QUEUED → RUNNING → terminal` over the WebSocket → open run detail → the "Repository execution" panel shows the commit SHA + image digest + exit code, and the "Test items" table shows the parsed pass/fail rows + an artifact link |
| **scheduled-run twice-fires-distinct-snapshot** | `RepositoryScheduleFireIT` (sibling of `SchedulingTickIT`): a `RECURRING` schedule on a repo-case suite fires twice against a fixture whose branch head moves between fires ⇒ two `test_runs` + two `repository_run` rows with **different `commit_sha`**, each frozen and immutable; a retry of the first run re-runs its original SHA |

**Additional API / persistence:** `RepositoryConnectionControllerIT` (CRUD, org isolation, `credential_ref` never echoed as a token, soft-delete-in-use ⇒ `409`); `TestRepositoryConnectionIT` (probe via MockWebServer, the 31st call in the hour ⇒ `429`, one `@Audited` row written with `org_id`); `SchemaMigrationIT` (version list `1..25`, all new enum columns `VARCHAR + CHECK`, no PG enum types, `repository_connection` / `repository_run` / `repository_test_item` all `org_id NOT NULL`).

**Shared events:** `EventBackwardCompatibilityTest` gains "captured v4 `RunRequestedEvent` / `RunCompletedEvent` still deserialises under the v5 records" and "`ResultChunkEvent` v2 round-trips"; `EventContractTest` / `EventSerializationRoundTripTest` gain the new records and enums. All stay green.

**Unit:** `RepoRefResolverTest` (per provider, above), `RepositoryRunSnapshotImmutabilityTest`, `ExecutionRunnerResolverTest`, `JUnitXmlReportParserTest`, `K6SummaryReportParserTest`, `WorkspacePathResolverTest`, `RepoRedactionTest`, `RepositoryCancellationTest`, `DockerContainerRunnerSpecTest` (asserts the built `HostConfig` — `CapDrop=[ALL]`, `ReadonlyRootfs`, `no-new-privileges`, `PidsLimit`, `Memory`, `NetworkMode`, no socket bind, labels).

**Root gate:** `mvn -B -ntp verify` in per-package fresh-JVM batches across all 4 Maven modules (Docker / browser ITs `@Tag`-gated; CI provisions a Docker daemon, already present for the existing Testcontainers ITs); `mvn -B -ntp -DskipITs verify`; `apps/web` lint + typecheck + vitest + build; `docker compose up` full stack + the `repository-run` Playwright smoke.
