# QualityOps Worker

**Status:** Phase 2A extraction (`docs/architecture/decisions/002-worker-extraction.md`);
real API-test execution added in Phase 2B1 (`docs/architecture/decisions/003-real-api-execution.md`);
real declarative browser-test execution added in Phase 2B2 (`docs/architecture/decisions/004-playwright-browser-execution.md`);
durable artifact upload + per-case `results.chunk` + bounded in-run retry + `secretRef`
resolution added in Phase 2B3 (`docs/architecture/decisions/005-artifact-storage-and-result-streaming.md`).

Spring Boot app (`com.qualityops.worker`). Consumes run-execution commands from
Kafka, executes each snapshot case (real browser, real HTTP, or simulated), and
publishes run lifecycle facts back to Kafka. It has a datasource **scoped to a
dedicated `worker` schema** (a single table, its own Flyway stream) used only as
a durable execution-attempt / side-effect ledger — it never reads or writes any
API-owned `public` table, and it never connects to Redis. The API remains the
sole writer of authoritative run/result state.

The runtime container image is based on `mcr.microsoft.com/playwright/java`
(bundled Chromium + OS libraries, glibc/jammy, **~2 GB**); the Maven build stage
stays on the small Alpine image.

## Responsibilities
- Consume `runs.requested` (group `worker-execution`).
- **Claim** each attempt durably in `worker.execution_attempt`
  (`INSERT … ON CONFLICT`); an already-`COMPLETED` claim ⇒ re-publish the cached
  terminal event and stop — so a redelivery (incl. across a restart) never
  re-executes.
- Select a runner **per case** (`ExecutionRunnerResolver`, precedence
  `browserTest > apiRequest > simulated`): `BrowserExecutionRunner` (embedded
  Playwright for Java) when the case carries a `BrowserTestSnapshot`;
  `ApiExecutionRunner` (real HTTP via the JDK `HttpClient`) when it carries an
  `ApiRequestSnapshot`; otherwise `SimulatedExecutionRunner`. Controlled by
  `qualityops.worker.execution.mode` (`simulated | real | auto`, default `auto`).
- Publish `runs.started`, then exactly one of `runs.completed` (v3: outcome +
  frozen snapshot + a lightweight per-case summary) / `runs.failed` (interrupt or
  harness fault only, with a generic redaction-safe reason).
- **Browser execution** (`BrowserExecutionRunner` + `PlaywrightBrowserDriver`
  behind the `PlaywrightBrowser` port): a **declarative** scenario only — navigate
  / click / fill / select / press-key steps and text / URL / visibility /
  element-state assertions; stable selectors preferring role, label and test-id.
  No user-supplied JavaScript or shell. All Playwright calls are confined to a
  single-thread executor. A **fresh `BrowserContext`** is created per execution;
  `page` → tracing → `context` are closed in a guarded `finally`, and a
  hard-timeout path `cancel(true)`s the future and force-recycles the shared
  `Browser`. `startUrl` and every `NAVIGATE` URL are SSRF-validated with the same
  `TargetValidator`; private/loopback/metadata **sub-resources** are intercepted
  and aborted (`block-private-subresources`, default on). `FILL` values are never
  logged/emitted (length only); URLs, DOM text and error messages are redacted;
  element text is stored only when `persist-body-snippets` is true. Screenshots
  (on failure) and traces go to a **temp directory**, size-capped, and are swept
  every 30 min — no artifact bytes cross the Kafka wire.
- Outbound HTTP is SSRF-validated (`TargetValidator`: every resolved IP
  denylist-checked; redirects off; loopback / link-local / metadata blocked, dev
  allowlist for private hosts) and redacted (`Redactor`: credential headers
  masked, raw bodies never stored, response memory bounded to `maxResponseBytes`
  via a streaming `BodySubscriber`). `persist-body-snippets` (default false) also
  suppresses the stored `BODY_CONTAINS` actual value.
- Three `@Scheduled` sweeps: ledger rows older than `attempt-retention` (14d),
  browser *capture* temp files older than `browser.artifact-retention` (1h), and
  artifact *staging* files older than `artifacts.staging-retention` (2h).
- **Durable artifacts (2B3, ADR-005).** After a case's in-run retries, each
  capture file is staged, hashed, and uploaded best-effort via `ArtifactStoragePort`
  → `S3ArtifactStorage` (MinIO Java client; write-only key) to a private bucket
  under an org-first key `org/<orgId>/run/.../attempt/<n>/<type>/<file>`, SSE-S3,
  a retention lifecycle rule (`BucketBootstrap`). The upload is bounded by
  `artifacts.upload-timeout` (10s) and **can never delay or fail the terminal** —
  failure ⇒ `ArtifactReference` status `UNAVAILABLE`. The API presigns GET URLs
  with a separate read-only credential.
- **`results.chunk` (2B3).** One `ResultChunkEvent` per case (key = runId), after
  its retries + upload; the v4 terminal re-carries the same `attemptEpoch` +
  `ArtifactReference[]` on each `CaseResultSummary` so a lost chunk is reconciled.
- **Bounded in-run retry (2B3).** `RunExecutionService.runCases` re-runs a
  transient `TIMEOUT`/`ERROR` only, with `SideEffectClass == NONE_OBSERVED`
  (never after a seen response status / an interactive browser step / `FAILED` /
  `BLOCKED`) and wall-clock budget room. No scheduler, no queue.
- **`secretRef` (2B3).** `HttpHeader.secretRef` / `BrowserStep.secretValue` are
  resolved by `EnvFileSecretResolver` (`QUALITYOPS_SECRET_<KEY>` env, then an
  optional properties file) at execution time; the plaintext never reaches an
  event, `config_snapshot`, a log, a result, or (by default) an artifact —
  secret-sourced headers are always masked, secret-bearing screenshots are gated
  (`artifacts.upload-secret-cases`, default false) with input masking + forced
  trace-off, and an unresolvable `secretRef` ⇒ case `BLOCKED`.

## Not in this phase (Phase 2C+)
- Queue-driven retry / re-dispatch, scheduling, queue state, per-tenant fairness.
- Durable worker-side upload queue + retry sweep (2B3 upload is best-effort synchronous).
- WebSocket push of `results.chunk` to the dashboard (2E).
- Azure Blob / Azure Key Vault adapters (Phase 5).
- A `secretRef` indirection so browser `FILL` credentials are resolved from a
  secret store instead of plaintext test definitions.
- A reaper for a run stuck `RUNNING` with no redelivery (Phase 2D).

## Run locally
```bash
mvn -B -ntp -pl apps/worker -am spring-boot:run
```
Requires Kafka and PostgreSQL (see `infra/compose/docker-compose.yml`).

## Configuration
| Env var | Default | Purpose |
|---|---|---|
| `KAFKA_SERVERS` | `localhost:29092` | Kafka bootstrap servers |
| `SERVER_PORT` | `8081` | Actuator port (`/actuator/health`, `/health/liveness`, `/health/readiness`) |
| `DB_URL` | `jdbc:postgresql://localhost:5432/qualityops` | Datasource (`worker` schema only) |
| `DB_USER` / `DB_PASSWORD` | `qualityops` | Datasource credentials |
| `WORKER_EXECUTION_MODE` | `auto` | `simulated` \| `real` \| `auto` |
| `WORKER_SSRF_ALLOW_PRIVATE` | `false` | Dev only — allow named private hosts |
| `WORKER_SSRF_ALLOWED_HOSTS` | *(empty)* | Comma-separated host allowlist when the above is on |

Full execution tuning lives under `qualityops.worker.execution.*` in
`application.yml` (timeouts, response-size cap, wall-clock budget, claim lease,
redaction patterns):

| Key | Default | Purpose |
|---|---|---|
| `qualityops.worker.execution.persist-body-snippets` | `false` | When off, stored `BODY_CONTAINS` / browser text actuals are suppressed |
| `…​.browser.enabled` | `true` | Master switch for the browser runner (off ⇒ browser cases are `BLOCKED`) |
| `…​.browser.headless` | `true` | Launch Chromium headless |
| `…​.browser.test-timeout` / `max-test-timeout` | `60s` / `3m` | Per-scenario budget and its hard clamp |
| `…​.browser.step-timeout` / `navigation-timeout` | `15s` / `30s` | Per-step and per-navigation Playwright timeouts (clamped to the test budget) |
| `…​.browser.launch-timeout` | `30s` | Browser process launch timeout |
| `…​.browser.hard-kill-grace` | `5s` | Extra time past the scenario budget before `cancel(true)` + force-recycle |
| `…​.browser.capture-trace` | `false` | Record a Playwright trace (kept only on failure) |
| `…​.browser.screenshot-on-failure` | `true` | Capture a screenshot when a step/assertion fails |
| `…​.browser.block-private-subresources` | `true` | Abort page sub-resource requests that resolve to denylisted addresses |
| `…​.browser.artifact-temp-dir` | `${java.io.tmpdir}/qualityops-browser` | Temp *capture* dir for screenshots/traces (swept, never durable) |
| `…​.browser.artifact-max-bytes` | `5242880` | Drop a *captured* artifact file larger than this (size still recorded) |
| `…​.browser.artifact-retention` | `1h` | Age after which the capture sweeper deletes temp files |
| `…​.artifacts.enabled` | `true` | Kill switch — off ⇒ every artifact ref is `UNAVAILABLE:store-disabled`, no MinIO client is built |
| `…​.artifacts.endpoint` / `bucket` | `http://localhost:9000` / `qualityops-artifacts` | MinIO S3 endpoint + private bucket |
| `…​.artifacts.access-key` / `secret-key` | `qualityops` / `qualityops-dev-secret` | **Write-only** key (dev; prod via mounted secret) |
| `…​.artifacts.sse` | `S3` | `NONE` \| `S3` — SSE-S3 header on PUT |
| `…​.artifacts.upload-timeout` | `10s` | Per-file bound on the per-case upload path (never fatal) |
| `…​.artifacts.max-artifact-bytes` | `10485760` | Files above this are `UNAVAILABLE:too-large` (not uploaded) |
| `…​.artifacts.retention-days` | `30` | Bucket lifecycle expiry (`BucketBootstrap`) |
| `…​.artifacts.staging-dir` / `staging-retention` | `${java.io.tmpdir}/qualityops-artifact-staging` / `2h` | Staged-file dir + `ArtifactStagingSweeper` age |
| `…​.artifacts.bootstrap-enabled` | `true` (false in tests) | `BucketBootstrap` ApplicationRunner (bucket + lifecycle rule) |
| `…​.artifacts.upload-secret-cases` | `false` | Gate for secret-bearing screenshots/traces (`UNAVAILABLE:suppressed-secret-case` when off) |
| `…​.retry.enabled` / `max-attempts` | `true` / `2` | Bounded in-run retry (1 original + N−1 retries) |
| `…​.retry.retryable-statuses` / `backoff` | `TIMEOUT,ERROR` / `0s` | Only these statuses, only with `SideEffectClass.NONE_OBSERVED` + budget room |
| `…​.secrets.env-prefix` / `file` | `QUALITYOPS_SECRET_` / *(empty)* | `secretRef` resolution sources (env var, then optional properties file) |
