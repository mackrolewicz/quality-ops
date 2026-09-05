# ADR-005: Durable test-artifact storage, per-case result streaming, bounded in-run retry, and `secretRef` credential indirection

## Status

Accepted.

- **Extends** ADR-003 §1 (the `ExecutionRunner` port and `CaseExecutionResult` gain retry/side-effect and artifact fields) and ADR-004 §6 (delivers the "browser credentials, later" `secretRef` follow-up and the "durable MinIO/Blob storage is Phase 2B3" follow-up).
- **Preserves** ADR-002 §1 — the API is still the sole writer of authoritative **run/result relational state** (`test_runs`, `test_results`, and the new `test_result_artifacts`). The Worker writes only Kafka events, `worker.execution_attempt`, and opaque blobs to a private object bucket.
- **Preserves and clarifies** ADR-003 §3 — the Worker's **Postgres** reach stays exactly one table in its own `worker` schema. The object store is not Postgres and not authoritative relational state; it is a blob side-store analogous to Kafka (which both apps already use). The Worker gets **write-only** bucket access; the API gets **read-only** bucket access. See §1.5.
- Realises `docs/product/PHASE-2-PLAN.md` §2B, increment **2B3** (`results.chunk` per-case streaming, artifact storage, retries), and narrows that increment's text: the migration is `V11` (not `V8`), the chunk event is `ResultChunkEvent`, and retry is **bounded in-run** (not queue-driven).

## Context

Phase 2B1 (ADR-003) and 2B2 (ADR-004) gave the Worker real API and browser runners behind a per-case `ExecutionRunner` port, an SSRF-safe `TargetValidator`, a `Redactor`, an `executionId`-guarded lifecycle, and a durable `worker.execution_attempt` claim ledger. Two capabilities were explicitly deferred to 2B3, plus two were named as follow-ups:

1. **Durable artifact storage.** Browser screenshots and traces are written to a temp directory, size-capped, and swept every 30 min (ADR-004 §6). They never leave the Worker host and cannot be shown in the dashboard.
2. **Per-case result streaming.** `runs.completed` carries a `List<CaseResultSummary>` (ADR-003 §6) delivered as one batch at the end. There is no per-case signal as cases finish, and no channel wide enough to carry artifact references.
3. **Bounded retry** for a case that ended `TIMEOUT`/`ERROR` (a transient fault), distinct from a genuine `FAILED` assertion.
4. **`secretRef` indirection** so a browser `FILL` password or an API `Authorization` header is authored as an opaque key and resolved from a secret store at execution time, never as plaintext in the test definition, the snapshot, or any event (ADR-004 §6).

Constraints carried from ADR-001/002/003/004: multi-tenancy on every event, row, and object key; idempotency under Kafka at-least-once; runs are immutable (config snapshotted at trigger); the API is the sole writer of authoritative state and never calls the Worker synchronously; boring, reversible technology; Flyway for all schema changes; no arbitrary user JavaScript or shell; a terminal lifecycle event must always be publishable.

Three cross-cutting invariants shape every decision below:

- **The terminal event must never be blocked or failed by an artifact upload.** Object-store availability cannot become a dependency of run completion.
- **`runs.completed` stays authoritative.** If every `results.chunk` is lost, the API must still produce correct `test_results` **and** `test_result_artifacts` rows from the terminal alone.
- **A retry must not double-charge an external side effect.** An API call whose response was seen, or a browser scenario past its first interactive step, must not blindly re-run.

## Decision

### 1. `ArtifactStoragePort` + a single S3-compatible adapter (MinIO now, Azure Blob later)

#### 1.1 Port shape (Worker)

New output port `com.qualityops.worker.execution.application.port.out.ArtifactStoragePort`, worker-domain types only — **no MinIO/S3/AWS SDK type crosses it**:

```
StoredArtifact put(ArtifactUpload upload) throws ArtifactStoreException;
```

- `ArtifactRef(UUID orgId, UUID runId, UUID executionId, UUID testCaseId, int attemptEpoch, ArtifactType type, String filename)` — pure worker domain; the adapter derives the storage key from it (§1.2).
- `ArtifactUpload(ArtifactRef ref, Path source, String contentType, long sizeBytes, String sha256)` — a staged local file plus its precomputed SHA-256.
- `StoredArtifact(String storageKey, String contentType, long sizeBytes, String sha256, boolean deduped)` — `deduped` is true when the adapter found an object already present at the key with a matching `sha256` metadata header and skipped the transfer.
- `ArtifactType` (worker enum): `SCREENSHOT, TRACE, HAR, CONSOLE_LOG, HTTP_EXCHANGE, REPORT`. 2B3 produces only `SCREENSHOT` and `TRACE`; the rest are reserved so later increments add them without a port or event change.

There is **no** `get`, `list`, or presign operation on this port. The Worker only ever writes. Presigning is API-side (§1.5).

#### 1.2 Bucket and key layout

- **One private bucket per deployment environment**, name from config (`qualityops.worker.execution.artifacts.bucket`, default `qualityops-artifacts`). Not one-bucket-per-tenant: bucket sprawl, per-bucket limits, and provisioning friction with no isolation benefit that a key prefix plus org-scoped presign does not already give.
- **Block-public-access**: the bucket policy denies `s3:GetObject` to `*`; no anonymous access; objects are reachable only through an API-minted presigned URL.
- **Path-addressed, org-first key**:

  ```
  org/<orgId>/run/<runId>/execution/<executionId>/case/<testCaseId>/attempt/<attemptEpoch>/<artifactType>/<filename>
  ```

  - **Org-first** because per-tenant isolation must be visible and enforceable in the key itself: it enables per-tenant lifecycle/retention rules, per-tenant storage quotas (2E/Phase 5), per-tenant bulk export and erasure (GDPR), and a cheap `prefix=org/<orgId>/` listing. The API additionally checks that the `org/<orgId>/` segment of any key it signs matches the caller's `orgId` (defence in depth on top of the row-level `org_id` check).
  - `run/<runId>` groups everything for one run — the download endpoint lists by this prefix.
  - `attempt/<attemptEpoch>` isolates retries: attempt 0's screenshot and attempt 1's screenshot never collide (§3).
- **Path-addressed, not content-addressed.** The key is a deterministic function of IDs; the SHA-256 is stored as object metadata (`x-amz-meta-sha256`) and returned on `StoredArtifact` for integrity and cheap re-PUT dedup. Content-addressing (key = hash) is rejected: it needs a separate key→object index table to ever locate an artifact, it defeats prefix-based retention and quota, and cross-tenant dedup of identical bytes is an information leak (one tenant could infer another stored the same object). Re-PUT to the same deterministic key (e.g. a redelivered case that re-uploads) is naturally idempotent — identical bytes overwrite identical bytes, or are skipped when `deduped`.

#### 1.3 Encryption and retention

- **SSE-S3** (`AES256`, server-managed keys), on by default via `qualityops.worker.execution.artifacts.sse` (`NONE | S3`, default `S3`). SSE-S3 is the one mode that needs zero key management, is a single flag on MinIO, is native on AWS S3, and is a no-op on Azure Blob (encrypted at rest by default) — so the MinIO-now / Blob-later story needs no code change. SSE-C is rejected (the Worker and the API would both have to hold and transmit the key on every call; losing it loses the data). SSE-KMS is deferred to Phase 5 (it needs a KMS / Key Vault, which is Phase 5); the config value is an enum so `KMS` is an additive addition.
- **Lifecycle rule**: expire objects `qualityops.worker.execution.artifacts.retention-days` (default **30**) after creation. Applied once at bucket-bootstrap time — by the `minio-bootstrap` compose service in dev (`mc ilm rule add`) and by an idempotent `ApplicationRunner` (`BucketBootstrap`) in the Worker that creates the bucket and the rule if absent (disabled under tests via `qualityops.worker.execution.artifacts.bootstrap-enabled=false`).
- **The temp-file sweepers stay; their role narrows.** ADR-004's `BrowserArtifactSweeper` still sweeps the browser **capture** temp dir every 30 min (1h retention) — files freshly captured by Playwright before they are staged. A new `ArtifactStagingSweeper` sweeps the **staging** dir (`artifacts.staging-dir`, 2h retention) — files that were captured, staged for upload, and either uploaded (safe to delete) or failed to upload (kept up to 2h for the operator, then dropped). ADR-003's `AttemptRetentionSweeper` is unchanged. Durable retention is the object store's job (lifecycle rule); local retention is the sweepers' job.

#### 1.4 Upload path — synchronous, best-effort, per-case, never fatal

After a case finishes (including all in-run retries, §3), for each artifact file the case produced:

1. The Worker stages the file into `artifacts.staging-dir`, computes its SHA-256 and size. A file larger than `artifacts.max-artifact-bytes` (default 10 MiB) is **not** uploaded — it is recorded `UNAVAILABLE` with reason `too-large`.
2. The Worker calls `ArtifactStoragePort.put(...)` **on the execution thread** (a virtual thread), wrapped in a per-file timeout `artifacts.upload-timeout` (default 10s) and a `try`/`catch`.
   - **Success** ⇒ an `ArtifactReference(type, storageKey, contentType, sizeBytes, AVAILABLE)` is added to the case's `results.chunk` (§2) and buffered for the terminal (§2.4). The staged file is deleted.
   - **Failure / timeout** ⇒ log a redacted line, record `ArtifactReference(type, null, null, null, UNAVAILABLE)` with a reason category (`store-unreachable`, `timeout`, `too-large`), leave the staged file for `ArtifactStagingSweeper`, and **continue**. The case verdict, its chunk, and the terminal event are unaffected.
3. The terminal `runs.completed` / `runs.failed` is **never** delayed or failed by an upload problem. If the object store is entirely down, every artifact is `UNAVAILABLE` and the run still completes with correct verdicts.

**Why synchronous-with-timeout and not async fire-and-forget + retry-sweep:** an async design needs a durable worker-side upload queue (a second `worker` table, which ADR-004 explicitly declined) and a way to attach a `storageKey` to an already-published, terminal-per-case chunk (a "backfill" event the API would have to reconcile). That machinery is disproportionate for 2B3's volume (1–2 artifacts per case, tens of KB to a few MB). Synchronous keeps the code path linear and the failure mode trivial (`UNAVAILABLE`). The **critical path is run completion**, and the upload is provably off it (a failed or slow upload cannot delay or fail `runs.completed`); the upload sits on the *per-case* path, bounded by a 10s timeout, which is acceptable because per-case latency already includes the full HTTP or browser execution. A durable upload queue with a retry sweep is noted as a deferred hardening (§"Deferred").

#### 1.5 Object-store boundary and the API's read path

- The **Worker** uses a MinIO client configured with a **write-mostly** access key — a distinct MinIO user (`qualityops-artifacts-rw`) bound to an `artifacts-rw` policy of `s3:PutObject` **plus `s3:GetObject`** on `qualityops-artifacts/*` (the `GetObject` grant is only for the `statObject` re-PUT dedup in §1.2; the Worker never lists, deletes, presigns, or touches bucket config). The root pair is never handed to an app; it is used only by the one-shot `minio-bootstrap` provisioner.
- The **API** uses a *separate* MinIO client configured with a **read-only** access key (a distinct MinIO user `qualityops-artifacts-ro` bound to an `artifacts-ro` policy: `s3:GetObject` on `qualityops-artifacts/*` only) plus `getPresignedObjectUrl(GET, …)`. It never writes.
- **This does not violate ADR-003 §3.** That rule constrains the Worker's **Postgres** reach (authoritative relational state) to `worker.execution_attempt`. The object store is a blob side-store, not Postgres, exactly as Kafka is — and both apps already produce and consume Kafka. The invariant that matters — *the API is the sole writer of authoritative run/result state* — is intact: the Worker writes opaque blobs plus artifact **references on events**; the API remains the only writer of `test_results` and `test_result_artifacts`. Two non-overlapping capabilities (write-only vs read-only) on one bucket, enforced by two credentials.
- **Presigned GET URLs are minted only by the API**, per request, with TTL `qualityops.artifacts.presign-ttl` (default 300s, clamped to max 900s), on the two endpoints in §"API design" below. Authorization: the caller's `orgId` (from the JWT) must equal the `test_result_artifacts.org_id`, **and** the storage key's `org/<orgId>/` segment must match. A mismatch ⇒ `404` (never confirm existence of another tenant's object). The Worker never mints a URL and never returns bytes to the API.

#### 1.6 Kubernetes / Azure Blob later

The port stays. The move to Azure Blob (Phase 5, already in ROADMAP) is a **new adapter** `AzureBlobArtifactStorage` implementing `ArtifactStoragePort` with `com.azure:azure-storage-blob` + managed identity, plus config changes (`endpoint` → account URL, `bucket` → container, credentials → managed identity). **What stays byte-for-byte identical:** the key layout (§1.2), the port signature, `ArtifactRef` / `StoredArtifact` / `ArtifactUpload`, every `com.qualityops.events` record, the `results.chunk` contract, and the API's presign endpoints (Azure "user delegation SAS" replaces the S3 presign call behind the API's `ArtifactUrlSigner` port).

### 2. `results.chunk` — per-case streaming topic

#### 2.1 Topic

- New topic **`results.chunk`** (`<domain>.<action>`, per `.claude/rules/kafka-events.md`).
- **Key = `runId`** (String), matching every other run topic. This co-partitions a run's chunks with its `runs.completed` and gives per-run ordering. `executionId` as key is rejected: it would scatter a run's chunks away from its terminal across partitions and break co-partitioning, for no benefit (`executionId` is 1:1 with `runId` today).
- **Ordering guarantee needed: none beyond per-run.** Chunks for different cases may interleave arbitrarily; the API upsert is commutative per `(runId, testCaseId)` and monotone in `attemptEpoch` (§2.3). A chunk may precede or follow the terminal — both are handled (§2.4).
- Partitions: broker default (compose = 1). A `@Bean NewTopic("results.chunk")` is declared on the Worker (producer) so non-auto-create environments get it; `results.chunk.DLT` likewise on the API (consumer).

#### 2.2 Event record — standalone, not in the `RunEvent` seal

`packages/shared-events` gains `com.qualityops.events.ResultChunkEvent`:

```
public record ResultChunkEvent(
        UUID eventId, UUID correlationId, UUID orgId, UUID runId, UUID executionId,
        Instant occurredAt, int schemaVersion,          // SCHEMA_VERSION = 1
        UUID testCaseId,
        int attemptEpoch,
        CaseResultSummary.Verdict verdict,
        long durationMillis,
        String firstFailureReason,                       // nullable, PRE-REDACTED by the Worker
        List<ArtifactReference> artifacts                // never null; may be empty
) { public static final int SCHEMA_VERSION = 1; }
```

`ArtifactReference` (new shared record):

```
public record ArtifactReference(
        ArtifactType artifactType,      // SCREENSHOT | TRACE | HAR | CONSOLE_LOG | HTTP_EXCHANGE | REPORT
        String storageKey,              // nullable ⇒ status == UNAVAILABLE
        String contentType,             // nullable when unavailable
        Long sizeBytes,                 // nullable when unavailable
        Availability status,            // AVAILABLE | UNAVAILABLE
        String unavailableReason        // nullable; category only, redaction-safe
) { public enum Availability { AVAILABLE, UNAVAILABLE } }

public enum ArtifactType { SCREENSHOT, TRACE, HAR, CONSOLE_LOG, HTTP_EXCHANGE, REPORT }
```

**No bytes, no presigned URL, no raw snippet on the wire.** `firstFailureReason` is the same redacted string the Worker already computes for `CaseResultSummary`.

`ResultChunkEvent` is a plain record (like `CaseResultSummary`, `ApiRequestSnapshot`), **not** added to the `sealed interface RunEvent permits {RunRequested,RunStarted,RunCompleted,RunFailed}`. It repeats the seven envelope fields by convention for tracing, but is deliberately outside the seal because: (a) the seal exists so a *lifecycle* dispatcher can `switch` exhaustively, and adding a fifth permitted type would force every such `switch` in both apps to change; (b) a result chunk is a different fact class (about a case, not a run transition) consumed by one dedicated single-purpose listener, never dispatched polymorphically; (c) it mirrors the existing separation between `RunLifecycleConsumer` and `RunCompletedConsumer` on the same topic.

#### 2.3 One chunk per case, terminal-per-case

Exactly **one** `ResultChunkEvent` is published per case, when that case completes (after its artifact uploads are attempted, §1.4). No progress/interim chunks in 2B3. Step-level browser progress and in-flight status are a **future option** for 2E's WebSocket work — additive, via a `chunkKind` discriminator or a `SCHEMA_VERSION` bump.

#### 2.4 API consumer and idempotent upsert

- New `@KafkaListener` class `ResultChunkConsumer` in `apps/api` `result/adapter/in/messaging/`, **group `api-results`** — the same group that already consumes `runs.completed` for `ResultService.generateResults`. Same group + `runId` key ⇒ one API instance owns both the chunks and the terminal for a given run's partition, so reconciliation runs on one instance with no cross-instance race. It delegates to a new input port `RecordCaseResultChunkUseCase` implemented by `ResultService` (result-writing stays in one service).
- **`test_results` gains `attempt_epoch INT NOT NULL DEFAULT 0`** (migration `V11`, §"Data model"). This is the "latest attempt wins" guard.
- On a chunk (and, identically, on each case of the terminal):
  1. **Org + execution guard** (identical to today's `generateResults`): `getRunUseCase.getDomain(runId, orgId)` — unknown/foreign ⇒ skip; `run.executionId()` must equal `event.executionId()` — stale ⇒ skip.
  2. **Epoch-guarded upsert** of the `test_results` row, keyed by the existing `uq_test_results_run_case (run_id, test_case_id)`:

     ```sql
     INSERT INTO test_results
         (id, org_id, run_id, test_case_id, status, duration_ms, error_message,
          retry_count, attempt_epoch, created_at)
     VALUES (gen_random_uuid(), :orgId, :runId, :caseId, :status, :durationMs, :reason,
             :attemptEpoch, :attemptEpoch, now())
     ON CONFLICT (run_id, test_case_id) DO UPDATE SET
         status        = EXCLUDED.status,
         duration_ms   = EXCLUDED.duration_ms,
         error_message = EXCLUDED.error_message,
         retry_count   = EXCLUDED.retry_count,
         attempt_epoch = EXCLUDED.attempt_epoch
     WHERE test_results.attempt_epoch <= EXCLUDED.attempt_epoch;
     ```

     A lower epoch is a no-op (a stale/reordered chunk from a stolen attempt). `retry_count = attempt_epoch` (0-based attempts ⇒ retry_count = number of retries).
  3. **Artifact upsert** for `(run_id, test_case_id, attempt_epoch, artifact_type)` into `test_result_artifacts` (§"Data model"): insert-or-update each reference; delete rows for the *same* result at a *lower* `attempt_epoch`; ignore a chunk whose `attempt_epoch` is lower than the row's current max.
  This must go through the repository port; the adapter uses a native `ON CONFLICT … WHERE` statement (JPA `saveAll` cannot express it).
- **`generateResults` changes** from "insert one row per case; skip entirely if `existsByRunId`" to "**upsert** one row per case from `event.caseResults()`, each at its own `CaseResultSummary.attemptEpoch` (§2.5), through the same epoch-guarded upsert, and upsert its `artifacts`". The `existsByRunId` hard-skip is removed. The legacy fabrication path (v1/v2 event, null/empty `caseResults`) is unchanged and also flows through the upsert at epoch 0.
- Net effect: chunks and terminal call the **same** idempotent, epoch-guarded, org-guarded upsert. Processing order is irrelevant; duplicate delivery is irrelevant; a lost chunk is corrected by the terminal.

#### 2.5 Authoritative-fallback invariant — `CaseResultSummary` carries the refs

`runs.completed.caseResults` stays the source of truth for verdicts. For **artifacts**, the resolution is:

**`CaseResultSummary` gains `int attemptEpoch` and `List<ArtifactReference> artifacts`** (both additive; a 4-arg convenience ctor keeps existing call sites compiling with epoch `0` and an empty list). The Worker buffers each case's final `attemptEpoch` and upload outcome and folds them into the terminal. Consequently:

- If **every** `results.chunk` is lost, `RunCompletedConsumer` still produces correct `test_results` **and** correct `test_result_artifacts` rows from the terminal alone. Chunks are a **latency optimisation** (the dashboard sees per-case results as they land), never a correctness dependency.
- This is a payload change to a nested record, so **`RunCompletedEvent.SCHEMA_VERSION 3 → 4`**.

Rejected: *chunks alone carry refs; a lost chunk ⇒ correct verdict but no artifact link.* That makes artifact visibility silently depend on Kafka retention and consumer health, which defeats the point of durable storage (an artifact you cannot find is nearly as bad as one never stored). The cost of the chosen option — re-serialising a handful of short strings on the terminal — is trivial.

#### 2.6 Schema-version impact and backward compatibility

| Record | Change | Version |
|---|---|---|
| `RunRequestedEvent` | nested `HttpHeader` / `BrowserStep` gain `secretRef` (§4) | `3 → 4` |
| `RunCompletedEvent` | nested `CaseResultSummary` gains `attemptEpoch` + `artifacts` (§2.5) | `3 → 4` |
| `RunStartedEvent`, `RunFailedEvent` | unchanged | — |
| `ResultChunkEvent`, `ArtifactReference`, `ArtifactType` | brand new | `ResultChunkEvent.SCHEMA_VERSION = 1`; no bump concept |
| `CaseResultSummary`, `HttpHeader`, `BrowserStep` | additive fields only | n/a (carrier records bump) |

- Nothing is renamed, moved, or retyped. `FAIL_ON_UNKNOWN_PROPERTIES=false` ⇒ v1/v2/v3 JSON deserialises under v4 records with the new fields null/empty. `spring.json.trusted.packages: com.qualityops.*` already covers the new records — **no Kafka config change**. `schemaVersion` stays advisory (nothing rejects a higher value).
- A shared-events JSON contract test pins: a captured v3 `RunRequestedEvent` / `RunCompletedEvent` still deserialises; `ResultChunkEvent` round-trips.

### 3. Bounded in-run retry for `TIMEOUT` / `ERROR`

#### 3.1 Policy

- **Retryable statuses: `TIMEOUT` and `ERROR` only.** Not `FAILED` (a genuine, deterministic assertion failure — retrying hides real bugs), not `BLOCKED` (SSRF/config — will always block), not `PASSED`.
- Config under `qualityops.worker.execution.retry.*`: `enabled` (default `true`), `max-attempts` (default **2** = 1 original + 1 retry), `retryable-statuses` (default `TIMEOUT,ERROR`), `backoff` (default `0s` — the run wall-clock budget is the only limiter; a small fixed backoff is allowed but off by default to conserve budget).
- Retries run **inline in `RunExecutionService.runCases`**: after a case returns a retryable status, if `attemptsSoFar < max-attempts` **and** the run wall-clock budget still has room for another `effectiveTimeout` **and** the failure is side-effect-safe (§3.3), re-invoke the *same resolved runner* with a fresh `CaseExecutionContext` whose new `attemptEpoch` field is incremented. Loop until `PASSED` / `FAILED` / `BLOCKED` / attempts exhausted / budget exhausted. **No scheduler, no queue, no re-published `runs.requested`** — entirely within one `processRunRequested` invocation.
- The case's final `CaseExecutionResult` is the **last** attempt's; only it becomes the case's `CaseResultSummary` / `ResultChunkEvent`. Intermediate attempts are logged.

#### 3.2 Per-case attempt counter — in-memory, no worker migration

Today `worker.execution_attempt.attempt_epoch` is **per-execution** (bumped on lease-steal). 2B3 needs a **per-case** counter. **Decision: an in-memory per-case counter** — a local variable in the `runCases` loop, carried on `CaseExecutionContext.attemptEpoch`, then onto the artifact key (`attempt/<n>/`), `ResultChunkEvent.attemptEpoch`, and `CaseResultSummary.attemptEpoch`. **Nothing is persisted in the `worker` schema; there is no `V2` worker migration.**

Rationale: the retry loop lives and dies inside one `processRunRequested` call; a redelivered or lease-stolen execution restarts *all* cases from index 0 (as today), so durability of the per-case counter buys nothing. The value is authoritative *on the wire*, and the API persists it in `test_results.attempt_epoch`; the epoch-guarded upsert (§2.4) makes "latest attempt wins" correct regardless of delivery. `worker.execution_attempt`'s one-table reach (ADR-003 §3) is untouched. A durable `worker.case_attempt` ledger is only needed if a future increment adds mid-run resume — deferred, and it would be `V2` in the append-only worker stream.

#### 3.3 No double-charging external side effects

`CaseExecutionResult` gains a worker-internal `SideEffectClass { NONE_OBSERVED, POSSIBLE }` (never on the wire). A case is retried only if its status is `TIMEOUT`/`ERROR` **and** `sideEffectClass == NONE_OBSERVED`.

- **API runner (`ApiExecutionRunner`):**
  - `NONE_OBSERVED` (retryable): DNS failure, connection refused, TLS handshake failure, connect timeout, or a total timeout with **no response status line received**; OR the request method is idempotent by HTTP semantics (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`) — the `Idempotency-Key: <executionId>` header (ADR-003 §3) is already sent on non-GET.
  - `POSSIBLE` (not retried): any failure *after* a response status line was received (response started, body read stalled); OR a `POST`/`PATCH` whose body was fully written with no server-confirmed response (the `Idempotency-Key` protects only a *cooperating* server, which we cannot assume).
- **Browser runner (`BrowserExecutionRunner`):**
  - `NONE_OBSERVED` (retryable): a `TIMEOUT`/`ERROR` during initial navigation to `startUrl` with **zero** `CLICK` / `SELECT` / `PRESS_KEY` steps executed (only the first page load was attempted), or a browser launch failure.
  - `POSSIBLE` (not retried): any `TIMEOUT`/`ERROR` after one or more interactive steps executed.
- **Simulated runner:** always `NONE_OBSERVED`.

Explicitly **safe** to retry: connection/DNS/TLS/connect failures, total timeout with no response bytes, browser launch failure, first-load navigation timeout with no interactions, any failure on an idempotent HTTP method. Explicitly **not** safe: any failure after a response status was seen, a non-idempotent request body fully sent with no confirmed response, any browser failure after an interactive step, all `FAILED`, all `BLOCKED`.

#### 3.4 Keying and reporting

A retried case's artifacts use `attempt/<attemptEpoch>` in the key (attempt 0 and attempt 1 never collide). The case's single `ResultChunkEvent` and its `CaseResultSummary` carry the **final** `attemptEpoch`. The API upsert's `WHERE test_results.attempt_epoch <= EXCLUDED.attempt_epoch` keeps the latest; `retry_count` mirrors it. `runs.completed.caseResults` reports the final attempt's verdict (already true — it is the last `CaseExecutionResult`).

### 4. `secretRef` indirection for `FILL` credentials and API `Authorization`

#### 4.1 Wire / schema shape — a distinct typed field

`packages/shared-events` gains `com.qualityops.events.SecretRef`:

```
public record SecretRef(String key) {}   // key matches [A-Z0-9_]{1,64}
```

Applied as a **nullable field alongside** the plaintext `value`, with an "exactly one of {value, secretRef}" rule:

- `HttpHeader(String name, String value, SecretRef secretRef)` — `value` nullable when `secretRef` is set. Affects `ApiRequestSnapshot.headers`. 2-arg convenience ctor kept.
- `BrowserStep(Action action, Selector target, String value, String key, SecretRef secretValue)` — for `FILL`, `secretValue` is the alternative to `value`. 4-arg convenience ctor kept.
- Body-level secrets (a password embedded in a JSON request body) are **out of scope for 2B3** — header values and `FILL` values only. A follow-up would add `List<SecretRef>` + `${SECRET:KEY}` placeholders in the body template, additively.

A distinct typed field is chosen over a `secret://<KEY>` sentinel string because a sentinel overloads a free-text field (a legitimate test could need the literal string), needs escaping rules, is easy to typo into a silent plaintext send, and cannot be validated independently. A typed field makes "is this a secret?" a null check, serialises cleanly, and takes its own `@Pattern`.

Because `HttpHeader` and `BrowserStep` are nested in `RunRequestedEvent`, **`RunRequestedEvent.SCHEMA_VERSION 3 → 4`** (§2.6).

#### 4.2 Authoring DTO validation (API)

`ApiRequestPayload` / `BrowserTestPayload` (and the module-local `ApiRequestSpec` / `BrowserTestSpec` chain through `TestCase`, `TestCaseEntity`, `TestCaseService`, `TestCaseResponse`, `RunConfigSnapshot`, `RunService.toWire*`): each header and each `FILL` step accepts either `value` or `secretRef` (`@Pattern("[A-Z0-9_]{1,64}")`), with `@AssertTrue` "exactly one of value/secretRef". The `secretRef` key is stored in `test_cases.api_request` / `browser_test` JSONB, frozen into `config_snapshot`, and put onto `RunRequestedEvent` **as the key only** — never resolved at authoring or trigger time. No new endpoints; `POST/PUT /api/v1/suites/{suiteId}/cases` and `/api/v1/cases/{id}` are reused (ADR-003 §7 / ADR-004 §8 precedent).

#### 4.3 Resolution — Worker port, env/file adapter now, Key Vault later

New output port `com.qualityops.worker.execution.application.port.out.SecretResolver`:

```
String resolve(String key) throws SecretNotFoundException;   // returns plaintext
```

- **Local/dev adapter `EnvFileSecretResolver`:** look up `<env-prefix><KEY>` (default prefix `QUALITYOPS_SECRET_`), then an optional mounted properties file (`qualityops.worker.execution.secrets.file`, keys = `<KEY>`). Env first, then file.
- **Production adapter is explicitly Phase 5:** `AzureKeyVaultSecretResolver` (`com.azure:azure-security-keyvault-secrets` + managed identity). Named here, not designed here. The port and every call site stay identical.
- **Resolution happens at execution time, inside the runner, immediately before use:** `ApiExecutionRunner` resolves header `secretRef`s while building the `HttpRequest`; `PlaywrightBrowserDriver` (via `SelectorMapper`) resolves a `FILL` `secretValue` immediately before `locator.fill(...)`. The plaintext lives only in a local variable for that one call and is never placed on `CaseExecutionResult`, `RequestMetadata`, `BrowserRunMetadata`, or any outcome.

#### 4.4 Guarantees and residual risk

The plaintext secret never enters: `RunRequestedEvent` / `config_snapshot` (only `SecretRef.key` is frozen); any log line (the resolver logs only key name + hit/miss); any `results.chunk` or `RunCompletedEvent`; any artifact (see below); `test_results`. In addition, **any header whose value came from a `secretRef` is always masked in `RequestMetadata`**, regardless of the redaction denylist (a new hard rule).

**Residual risk — a filled secret rendered in a screenshot or trace.** Consistent with ADR-004 §6:

- Screenshots/traces for a case that used **any** `secretRef` are **gated**: uploaded to durable storage only when `qualityops.worker.execution.artifacts.upload-secret-cases` is `true` (**default `false`**). When `false`, the artifact stays temp-only (swept in 1h, as today) and the `ArtifactReference` is emitted `UNAVAILABLE` with reason `suppressed-secret-case`.
- When a screenshot *is* taken for a secret-bearing case, the `FILL` targets' locators are passed to Playwright's `screenshot(new Page.ScreenshotOptions().setMask(locators))` so those inputs are painted over (best-effort — does not cover a page that reflects the value elsewhere).
- `captureTrace` is **forced off** for a secret-bearing case regardless of config (a trace captures DOM snapshots + network).
- Documented residual: a page that reflects a typed secret into non-masked DOM before a failure screenshot, with `upload-secret-cases` manually set `true`, can still persist it. The default-off gate + input masking + trace-off is the mitigation; full artifact DLP is out of scope.

**Unresolvable `secretRef` ⇒ case `BLOCKED`** (not `ERROR`). `BLOCKED` already means "we refused to run this case due to a config/safety problem; the run continues; aggregate `FAILED`" (SSRF uses it). A missing secret is a deterministic config problem and must **not** be retried — and `ERROR` is retryable (§3). Safe reason: `unresolved secret reference: <KEY>` (the key name is author-chosen and already in the snapshot; it is not itself sensitive).

This delivers the first half of ADR-004 §6's "browser credentials, later": the `secretRef` schema, the env/file resolver, execution-time resolution, and the screenshot gate. Key Vault remains Phase 5.

### 5. Local object store for tests and compose

#### 5.1 Testcontainers — MinIO

Integration tests use **`org.testcontainers:minio` (`MinIOContainer`)**, not LocalStack-S3:

| Dimension | MinIO | LocalStack-S3 |
|---|---|---|
| Image size | ~150 MB | ~700 MB+ |
| Startup | ~1–2 s | ~10–20 s for the S3 service |
| S3 fidelity | *is* an S3 server — full presign, bucket policy, SSE-S3, lifecycle | emulation with historical presign/SSE gaps |
| Repo fit | consistent with existing `:postgresql`, `:kafka`, `:junit-jupiter` | a heavier new idiom |
| Prod parity | dev/compose store is also MinIO | n/a |

Add `org.testcontainers:minio` (test scope, covered by the existing `testcontainers-bom`) to `apps/api/pom.xml` and `apps/worker/pom.xml`.

#### 5.2 Java client — MinIO Java client (`io.minio:minio`) for both apps

`io.minio:minio` for the Worker (`put`) **and** the API (presign GET + head), not AWS SDK v2 S3:

- One client library across both apps; smallest dependency tree that still speaks full S3 (bucket policy, SSE-S3 header, lifecycle, presigned URLs); first-class custom-endpoint + path-style access (which MinIO requires and AWS SDK needs extra config for); the repo already carries `okhttp3` (via `mockwebserver`) so the transitive `okhttp` is familiar.
- **AWS SDK v2 S3** (`software.amazon.awssdk:s3` + `S3Presigner`) is the documented alternative — heavier transitive tree (Netty or apache-client + optional aws-crt), and its value (IAM/STS, deep S3 semantics) is not needed for a lab whose cloud target is **Azure Blob**, not AWS. Neither SDK survives the cloud move — only the port does — so the lighter client wins now.
- Pin the version in the parent `dependencyManagement` (alongside `playwright.version`); add the dependency to both app poms.

#### 5.3 `docker-compose.yml` (base — MinIO is infra, like postgres/redis/kafka)

- `minio` service: pinned `minio/minio:<RELEASE-tag>`, `command: server /data --console-address ":9001"`, `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` from env, ports `9000` (API) + `9001` (console), `minio_data` named volume, healthcheck `curl -f http://localhost:9000/minio/health/live` (interval 5s / timeout 3s / retries 10 / start_period 10s).
- `minio-bootstrap` one-shot service: pinned `minio/mc:<RELEASE-tag>`, `depends_on: { minio: { condition: service_healthy } }`, `restart: "no"`, entrypoint runs `mc alias set`, `mc mb --ignore-existing local/qualityops-artifacts`, `mc anonymous set none …`, `mc ilm rule add --expire-days 30 …`, `mc admin user add local <ro-key> <ro-secret>`, `mc admin policy create local artifacts-ro /policies/artifacts-ro.json`, `mc admin policy attach local artifacts-ro --user <ro-key>`. Mounts `infra/compose/minio-policies/`.
- new `minio_data` volume.

#### 5.4 `docker-compose.dev.yml`

- `worker` env: `QUALITYOPS_ARTIFACTS_ENDPOINT=http://minio:9000`, `QUALITYOPS_ARTIFACTS_BUCKET=qualityops-artifacts`, `QUALITYOPS_ARTIFACTS_ACCESS_KEY`/`_SECRET_KEY` = root pair, `QUALITYOPS_ARTIFACTS_SSE=S3`, `WORKER_RETRY_MAX_ATTEMPTS=2`, `QUALITYOPS_SECRET_DEMO_PASSWORD=hunter2` (so a `secretRef` smoke case resolves). `depends_on`: `minio` healthy + `minio-bootstrap` `service_completed_successfully`.
- `api` env: `QUALITYOPS_ARTIFACTS_ENDPOINT=http://minio:9000`, `QUALITYOPS_ARTIFACTS_BUCKET=qualityops-artifacts`, `QUALITYOPS_ARTIFACTS_ACCESS_KEY`/`_SECRET_KEY` = the **read-only** pair, `QUALITYOPS_ARTIFACTS_PRESIGN_TTL=300s`. `depends_on`: `minio` healthy + `minio-bootstrap` completed.

#### 5.5 `Dockerfile.worker` and CI

- `Dockerfile.worker`: **no change** beyond the new Maven dependency (pulled in the build stage automatically). The MS Playwright runtime base already has `curl`; `mc` is not needed in the image (bootstrap is a separate compose service).
- `.github/workflows/ci.yml`: **no required change.** `backend-it` runs on `ubuntu-latest` with a host Docker socket; `MinIOContainer` needs nothing extra (no Docker-in-Docker — Testcontainers uses the host daemon, same as the existing postgres/kafka containers). `backend` (`-DskipITs`) stays MinIO-free — no unit test touches it. Optional: a `docker pull minio/minio:<tag>` warm step to shave ~2 s off the first IT.

## Consequences

### Positive

- Browser screenshots and traces are durably stored and downloadable from the dashboard via short-TTL, org-scoped presigned URLs — the API never proxies bytes and never mints a URL for another tenant.
- The dashboard sees per-case verdicts, durations, redacted reasons, and artifact links **as each case finishes**, not all-at-once at the end.
- Losing every `results.chunk` costs nothing: the v4 terminal reconciles `test_results` **and** `test_result_artifacts` exactly.
- A transient `TIMEOUT`/`ERROR` is retried once, inline, within the run budget, without a scheduler or a re-published request — and never when a side effect may already have fired.
- Credentials are authored as opaque keys; the plaintext never reaches an event, a snapshot, a log, `test_results`, or (by default) an artifact.
- The Worker's Postgres reach is still exactly `worker.execution_attempt`; the object store is a separate, write-only capability.
- MinIO now / Azure Blob later is a single new adapter behind `ArtifactStoragePort` — key layout, port, and every event stay identical.
- Every `test_results` / `test_result_artifacts` write is an org-guarded, `executionId`-guarded, epoch-monotone upsert to a deterministic business key: reprocessing any chunk or the terminal any number of times converges.

### Negative

- The Worker gains an object-store dependency: a client library, a bucket-bootstrap runner, a staging directory, a second sweeper, and per-case upload latency (bounded at `upload-timeout`).
- The API gains an object-store dependency (read-only) and two new endpoints, plus a new consumer, a new table, and a native upsert path where `saveAll` used to suffice.
- `RunRequestedEvent` and `RunCompletedEvent` both go to `SCHEMA_VERSION 4`; `CaseResultSummary`, `HttpHeader`, `BrowserStep` widen; `TestCase` / `TestCaseEntity` / request / response signatures widen for `secretRef`, rippling through tests.
- One `apps/api` migration (`V11`). MinIO joins the base compose stack (two services + a volume).
- `results.chunk` re-carries per-case verdict data that also rides the terminal — deliberate duplication for the fallback invariant.

### Risks

- **Upload latency vs lost-artifact trade-off.** A slow object store adds up to `upload-timeout × artifacts-per-case × cases` to run wall-clock. Mitigation: 10s per-file timeout, `max-artifact-bytes` ceiling, `artifacts.enabled=false` kill switch, and the run budget still terminates the run. `UNAVAILABLE` is strictly better than a stuck run.
- **Chunk/terminal reconciliation race.** A chunk and the terminal's summary for the same case at the same epoch race on the upsert — both carry identical data and `WHERE existing.attempt_epoch <= excluded` makes the loser a harmless rewrite. The terminal is always published after all retries, so it can never carry a *lower* epoch than a later chunk. Same `api-results` group + `runId` key ⇒ one consumer instance per run.
- **Retry double-execution edge.** The `SideEffectClass` heuristic is conservative but imperfect: a server that fully processes a request, responds, then drops the connection before the status line is read is classed `NONE_OBSERVED` and may be retried. Mitigation: `Idempotency-Key` on non-GET (ADR-003 §3); `max-attempts` default 2 bounds the blast radius to one extra call; documented that only idempotent-by-method or explicitly-idempotent endpoints are retry-safe.
- **Secret-in-screenshot residual.** Covered in §4.4 — default-off upload gate + Playwright input masking + forced trace-off; residual only when the gate is manually enabled and the page reflects the secret into unmasked DOM.
- **MinIO single-node durability in dev.** `server /data` on one drive, no erasure coding — losing `minio_data` loses all dev artifacts. Acceptable for a lab (artifacts are reproducible by re-running); prod is Azure Blob (Phase 5) with its own redundancy.
- **`results.chunk` consumer lag.** A backed-up `api-results` group delays per-case dashboard updates but **not** run completion (the separate `api-execution` group drives status) and **not** final correctness (the terminal reconciles). Lag metrics/alerting are 2D.
- **Rolling-deploy skew.**
  - *Old API + new Worker:* Worker publishes `results.chunk` (no consumer — messages age out at 24h retention) and `runs.completed` v4 (old API ignores the new nested fields via `FAIL_ON_UNKNOWN_PROPERTIES=false`, still writes verdicts, no artifact rows, `attempt_epoch` defaults 0). Safe, degraded.
  - *New API + old Worker:* no `results.chunk` produced (`ResultChunkConsumer` idle); `runs.completed` v3 upserts with `attemptEpoch=0` and empty `artifacts` — behaves like today plus a harmless upsert. A case authored with a `secretRef` on the new API but executed by the old Worker sends `value=null` ⇒ the old runner fills empty ⇒ the case **FAILS** (not a leak).
  - Package names are unchanged ⇒ no DLT storm (unlike ADR-002's rename). Deploy API + Worker together, as ADR-002/003/004 already require.

### Post-review refinements (applied before merge)

- **Deterministic object name.** The storage-key last segment is fixed per type
  (`screenshot.png` / `trace.zip`), not a random UUID, so a redelivered or
  lease-stolen re-run re-PUTs the *same* key — the `statObject` dedup (§1.2)
  actually engages and no objects are orphaned. The random UUID survives only as
  the on-disk staging filename.
- **`BucketBootstrap` fails fast** when the bucket is absent *and* cannot be
  created (a deployment expecting the Worker to self-provision must have a working
  store); a bucket that already exists but whose lifecycle rule cannot be set
  (e.g. a least-privilege key without `PutBucketLifecycle`) is logged and
  tolerated. Every `@SpringBootTest` IT sets `artifacts.bootstrap-enabled=false`
  (and `artifacts.enabled=false`) so this path runs only against a real
  `MinIOContainer` in `BucketBootstrapIT`.
- **Two least-privilege credentials, not the root pair.** `minio-bootstrap`
  provisions `qualityops-artifacts-rw` (`s3:PutObject` + `s3:GetObject`) for the
  Worker and `qualityops-artifacts-ro` (`s3:GetObject`) for the API; the root
  credential is used only by the one-shot provisioner. Bucket creation,
  public-access denial and policy creation in `minio-bootstrap` are fail-fast;
  only genuinely-idempotent steps (user-already-exists, policy-already-attached)
  are `|| true`, and the retention-rule step degrades to a warning rather than a
  silent success.

## Alternatives considered

### Artifact store client
- **AWS SDK v2 S3 (`software.amazon.awssdk:s3` + `S3Presigner`).** Rejected as the default: heavier transitive tree, and its IAM/STS/semantics value is moot when the cloud target is Azure Blob. Documented as the swap-in if the target ever becomes AWS.
- **Azure Blob SDK now.** Rejected: there is no Azure in the lab until Phase 5; MinIO is the local/dev store. The port makes Blob a Phase-5 adapter with zero contract change.
- **One bucket per tenant.** Rejected: bucket sprawl, per-bucket limits, provisioning friction; org-first key prefix + org-scoped presign gives the isolation, quota hook, and lifecycle hook without it.
- **Content-addressed keys (key = SHA-256).** Rejected: needs a key→object index table to ever locate an artifact, defeats prefix-based retention/quota, and cross-tenant byte-dedup is an information leak. Path-addressed with SHA-256 as metadata keeps re-PUT idempotent without those costs.
- **SSE-C / SSE-KMS.** SSE-C rejected (key-distribution problem across Worker + API; key loss = data loss). SSE-KMS deferred to Phase 5 (needs a KMS/Key Vault); the config enum leaves room for `KMS`.

### Upload path
- **Async fire-and-forget + durable retry sweep.** Rejected for 2B3: needs a second `worker` table and a chunk-backfill event; disproportionate for 1–2 small artifacts per case. Noted as deferred hardening.
- **Block the terminal until uploads succeed.** Rejected: violates "the terminal event must never be blocked by an upload".

### Result streaming
- **`ResultChunkEvent` inside the `RunEvent` sealed hierarchy.** Rejected: forces every exhaustive lifecycle `switch` in both apps to change; a result chunk is a different fact class consumed by one dedicated listener, never dispatched polymorphically.
- **Chunks alone carry artifact refs; the terminal stays as-is.** Rejected: artifact visibility would silently depend on Kafka retention and consumer health. `CaseResultSummary` carries the refs so the terminal is self-sufficient (`RunCompletedEvent` → v4).
- **Keep `runs.completed` batch-only, no `results.chunk` (defer to 2E).** Rejected: 2B3's brief is per-case streaming; the topic is cheap and the fallback invariant makes it low-risk.
- **Key `results.chunk` by `executionId`.** Rejected: breaks co-partitioning with `runs.completed`; no benefit while `executionId` is 1:1 with `runId`.

### Retry
- **Queue-driven / re-published `runs.requested` retry (as PHASE-2-PLAN §2B's "configurable retries" could be read).** Rejected for 2B3: a scheduler/queue is 2C. Bounded in-run retry inside `RunExecutionService` needs no new infrastructure and no new event.
- **Durable `worker.case_attempt` table for per-case attempts.** Rejected: the retry loop is entirely within one `processRunRequested` call; a redelivered/stolen execution restarts all cases anyway, so persistence buys nothing. In-memory counter + the API's epoch-guarded upsert is sufficient. Revisit only if mid-run resume is ever needed (would be worker `V2`).
- **Retry `FAILED` cases too (configurable).** Rejected: `FAILED` is a deterministic assertion result; retrying it masks real regressions and inflates pass rates. Only transient `TIMEOUT`/`ERROR` with no observed side effect.

### `secretRef`
- **`secret://<KEY>` sentinel string in the existing `value` field.** Rejected: overloads free text, needs escaping, easy to typo into a plaintext send, not independently validatable. A distinct typed `SecretRef` field with an "exactly one of" rule is unambiguous.
- **Resolve at trigger time in the API and freeze the plaintext into the snapshot.** Rejected outright: the plaintext would live in `config_snapshot`, `RunRequestedEvent`, and Kafka retention. Resolution must be at execution time in the Worker.
- **Unresolvable `secretRef` ⇒ `ERROR`.** Rejected: `ERROR` is retryable (§3); a missing secret is deterministic and must not be retried. `BLOCKED` (run continues, aggregate `FAILED`) matches the SSRF-block precedent.

### Local object store
- **LocalStack-S3.** Rejected: ~5× the image, ~10× the startup, weaker presign/SSE fidelity, a heavier new idiom than the repo's existing Testcontainers modules.
