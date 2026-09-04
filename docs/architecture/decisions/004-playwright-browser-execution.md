# ADR-004: Real declarative browser-test execution in the Worker (embedded Playwright for Java)

## Status
Accepted

Extends ADR-003 §1 (the `ExecutionRunner` output port gains a third
`RunnerKind`). Preserves ADR-002 §1 ("the API is the sole writer of authoritative
run/result state" — the browser runner writes nothing but
`worker.execution_attempt`) and ADR-003 §3 (the Worker's DB reach stays exactly
one table in its own `worker` schema). Realises PHASE-2-PLAN.md § 2B, increment
2B2.

## Context

Phase 2B1 (ADR-003) gave the Worker a real API runner: a per-case
`ExecutionRunner` port, `ExecutionRunnerResolver` (`simulated | real | auto`), an
SSRF-safe `TargetValidator`, a `Redactor`, a bounded response-body handler, an
`executionId`-guarded lifecycle, and a durable `worker.execution_attempt` claim
ledger that survives Worker restarts.

2B2 adds **real browser-test execution**: a declarative UI scenario (navigate,
click, fill, select, press-key, plus text / URL / visibility / element-state
assertions) executed against a real Chromium instance in a fresh, isolated
`BrowserContext`, with test / step / navigation / browser-process timeouts and
deterministic resource cleanup on success, failure, cancellation and timeout.

The open architectural question was **where the browser runs**:

- **A. Embed Playwright for Java in the Worker JVM.** `com.microsoft.playwright`
  drives a bundled Node "driver" process, which drives the browser processes.
- **B. A separate Node runner process/service.** The Worker hands scenarios to a
  standalone `@playwright/test`-based service over Kafka or HTTP; the service
  owns the browser lifecycle and reports results back.

Constraints carried from ADR-001/002/003: multi-tenancy on every event and row;
idempotency under Kafka at-least-once; runs are immutable (config snapshotted at
trigger); the API is the sole writer of authoritative state and never calls the
Worker synchronously; boring, reversible technology; Flyway for all schema
changes; **no arbitrary user-provided JavaScript or shell** in a test definition.

## Decision

### 1. Embed Playwright for Java behind the existing `ExecutionRunner` port

Add `com.microsoft.playwright:playwright` (compile scope) to `apps/worker`. A new
`BrowserExecutionRunner implements ExecutionRunner` (`kind() == BROWSER`) sits
behind the **unchanged** port and reuses everything 2B1 built: `TargetValidator`,
`Redactor`, `WorkerExecutionProperties`, `ExecutionRunnerResolver`, the
`worker.execution_attempt` ledger, and the Kafka lifecycle in
`RunExecutionService`.

All Playwright API is confined to one adapter, `PlaywrightBrowserDriver`, reached
through a thin worker-internal port:

```
com.qualityops.worker.execution.application.port.out.PlaywrightBrowser
    BrowserRunOutcome run(BrowserRunCommand command)   // never throws for a
        step/assertion failure or a scenario timeout — those are encoded in the
        outcome; throws only on an unrecoverable driver fault
    void forceRecycle()                                 // close + discard the
        shared Browser; safe from any thread; next run() relaunches
```

`BrowserRunCommand` / `BrowserRunOutcome` (and `BrowserStepOutcome`,
`BrowserAssertionOutcome`, `BrowserRunMetadata`, `BrowserStepStatus`) are pure
worker-domain records, independent of `com.qualityops.events`, shaped for 2B3
artifact storage exactly as `CaseExecutionResult` was in 2B1.

#### Embedded vs. separate Node runner

| Dimension | A. Embedded Playwright-Java (chosen) | B. Separate Node runner service |
|---|---|---|
| New deployable units | 0 — one adapter class + a base-image swap | 1 — a Node service, its image, its Kafka group / HTTP contract, its own deploy, scaling and on-call |
| Reuse of 2B1 | Full — same port, `TargetValidator`, `Redactor`, ledger, lifecycle | Re-implement SSRF, redaction, dedup, event contracts in TypeScript |
| Isolation | Fresh `BrowserContext` per execution; browser + driver already run as **subprocesses** of the Worker; hard-kill via `Browser.close()` from a watchdog | Process-level isolation is stronger, but not needed — the JVM never runs page script |
| Language boundary | One (JVM → bundled Node driver, managed by the library) | Two (JVM ↔ our Node service ↔ bundled driver) |
| Latency | In-process call to a local driver | Extra network hop + serialization per scenario |
| Kubernetes / independent scaling | Worker scales as one unit; browser load co-scales with execution load (acceptable for 2B2) | Independent scaling is real, but premature — no evidence execution is browser-bound yet |
| Image size | Worker image → MS Playwright base, ~2 GB (glibc/jammy) | Worker stays small; the weight moves to the new service's image |
| Swappable later | Yes — replace the adapter behind `PlaywrightBrowser`; no event-contract change | n/a |

Option A is the **simplest** choice that still preserves isolation (fresh
context per run; browser as a subprocess; deterministic cleanup) and a path to
Kubernetes execution (the Worker is already a Deployment; the browser scales with
it). It maximises reuse and adds the minimum new surface: no new deployable, no
second copy of the SSRF/redaction/dedup logic, no new wire contract.

**If a separate runner is adopted later** (e.g. execution proves browser-bound,
or per-scenario process isolation becomes a hard requirement): implement
`PlaywrightBrowser` as an adapter that publishes a `browser.scenario.requested`
command and awaits a `browser.scenario.completed` reply, and move
`PlaywrightBrowserDriver` into the Node service. `BrowserExecutionRunner`,
`ExecutionRunnerResolver`, the ledger, and every `com.qualityops.events` record
stay exactly as they are. The seam is deliberately drawn for that swap.

### 2. Declarative browser-test specification (no user code)

`packages/shared-events` gains four pure-JDK records:

- `Selector(Strategy strategy, String value, String roleName, String accessibleName)`,
  `Strategy ∈ {ROLE, LABEL, TEST_ID, TEXT, CSS}` — preference order role → label
  → test-id; `CSS` is the escape hatch. `ROLE` uses `roleName` (+ optional
  `accessibleName`); the rest use `value`.
- `BrowserStep(Action action, Selector target, String value, String key)`,
  `Action ∈ {NAVIGATE, CLICK, FILL, SELECT, PRESS_KEY}`. `NAVIGATE` carries an
  absolute URL in `value` and no target; `FILL` / `SELECT` carry `target` +
  `value`; `PRESS_KEY` carries `key`.
- `BrowserAssertion(Type type, Selector target, String expected)`,
  `Type ∈ {TEXT_EQUALS, TEXT_CONTAINS, URL_EQUALS, URL_CONTAINS, VISIBLE,
  ELEMENT_STATE}`. `ELEMENT_STATE.expected ∈ {enabled, disabled, checked,
  unchecked, editable, hidden}`.
- `BrowserTestSnapshot(String startUrl, List<BrowserStep> steps,
  List<BrowserAssertion> assertions, Integer testTimeoutMillis,
  Integer stepTimeoutMillis, Integer navigationTimeoutMillis)`.

There is no field anywhere that carries JavaScript, a shell command, or an
`page.evaluate` body. The runner maps each `BrowserStep` to a fixed Playwright
`Locator` call via `SelectorMapper`; anything the mapper cannot resolve (e.g. an
unknown ARIA role) becomes an `ERROR` step outcome, never an execution.

### 3. Worker execution model

- **Thread confinement.** Playwright-Java is not thread-safe. `PlaywrightConfig`
  provides a single-thread `playwrightExecutor` (named `playwright-*`).
  `PlaywrightBrowserDriver` owns one lazily-launched `Playwright` + `Browser`
  (Chromium, headless), guarded by a lock, and every `Page` / `Context` /
  `Locator` touch happens on that one thread. `run()` short-circuits to a direct
  call when already on the executor thread, so the `BrowserExecutionRunner`
  submitting `driver::run` onto the same executor never self-deadlocks.
- **Fresh context per execution.** `run()` opens `browser.newContext(...)` per
  command, sets default + navigation timeouts, `context.newPage()`, navigates to
  the validated `startUrl`, runs the steps (stopping early on the monotonic
  scenario deadline and recording the remainder as not-executed), then evaluates
  the assertions.
- **Deterministic cleanup.** A `finally` block closes `page`, then stops tracing,
  then closes `context` — quietly, each guarded — and **never** closes the shared
  `Browser`. On a `PlaywrightException` the driver takes a best-effort screenshot
  and returns an outcome with `Status.TIMED_OUT` (Playwright `TimeoutError`) or
  `Status.FAULT`.
- **Hard timeout.** `BrowserExecutionRunner` runs `driver.run` as a
  `CompletableFuture` on the executor and waits
  `ctx.effectiveTimeout() + browser.hardKillGrace()`. On `TimeoutException` it
  `future.cancel(true)` **and** `driver.forceRecycle()` (closes and discards the
  shared `Browser`; the next run relaunches), then returns a `TIMEOUT` case with
  whatever partial metadata exists. An `ExecutionException` → `forceRecycle()` +
  `ERROR` "browser unavailable". An `InterruptedException` restores the interrupt,
  cancels, recycles, and rethrows `ExecutionHarnessException` (→ `runs.failed`,
  generic reason). `@PreDestroy` + the executor's `destroyMethod` close the
  browser and driver on shutdown.
- **Per-case timeout budget.** `RunExecutionService.runCases` derives the browser
  case's `effectiveTimeout` from `props.browser().effectiveTestTimeout(
  spec.testTimeoutMillis())` (clamped to `browser.maxTestTimeout`), exactly as
  the API case derives its own. `CaseExecutionContext` is unchanged.

### 4. Runner selection (three-way)

`ExecutionRunnerResolver` becomes three-way with precedence
`browserTest > apiRequest > simulated`:

| mode | rule |
|---|---|
| `SIMULATED` | always simulated |
| `REAL` | `browserTest != null` ⇒ BROWSER; else API (a case with neither ⇒ `BLOCKED`) |
| `AUTO` (default) | `browserTest != null` ⇒ BROWSER; else `apiRequest != null` ⇒ API; else simulated |

`resolvedKindFor(cases)` (the ledger `runner_kind` hint) reports `BROWSER` if any
case is a browser case. A case that carries **both** `apiRequest` and
`browserTest` is rejected at authoring time (`@AssertTrue` on the request record +
a service guard); the resolver logs a warning and picks `BROWSER` defensively.

### 5. SSRF and network restrictions

- `BrowserExecutionRunner` pre-validates `spec.startUrl()` **and every
  `NAVIGATE` step's URL** through the same `TargetValidator` used by the API
  runner (URL shape → resolve → every A/AAAA vs. the denylist; userinfo
  rejected; http/https only). Any blocked target ⇒ the whole case is `BLOCKED`
  with a safe reason and the driver is never called.
- **Sub-resource interception.** With `browser.blockPrivateSubresources` (default
  true), the context installs a route handler that resolves each request's host
  and `route.abort()`s it if any resolved address is on the denylist (loopback,
  link-local incl. `169.254.169.254`, private, CGNAT, ULA…), else `route.resume()`.
  This catches an allowed page that embeds `<img src="http://169.254.169.254/…">`.
- Residual DNS-rebinding TOCTOU (Chromium re-resolves; it is not IP-pinned) is
  accepted for 2B2, same as ADR-003 §5. Mapped to OWASP A10:2021.

### 6. Redaction and artifact handling

- `finalUrl`, every assertion `actual`, and every `PlaywrightException` message
  that reaches an outcome pass through `Redactor`. A `FILL` step's `value` is
  **never** logged or returned — only its length.
- `TEXT_EQUALS` / `TEXT_CONTAINS` record the element's text in `actual` **only**
  when `qualityops.worker.execution.persist-body-snippets` is true (default
  **false**); otherwise `actual = "(text suppressed)"`. This mirrors the same
  flag now also applied to `BODY_CONTAINS` in `AssertionEvaluator` and to
  `firstFailureReason` in `ApiExecutionRunner`.
- Screenshots (on failure) and traces (on failure, when
  `browser.captureTrace` is on) are written to a **temp directory**
  (`browser.artifactTempDir`), capped at `browser.artifactMaxBytes` (a file over
  the cap is dropped, its byte size still recorded, its path left null).
  `BrowserRunMetadata` carries only the **temp path and size** — nothing crosses
  the Kafka wire (`CaseResultSummary` is unchanged). `BrowserArtifactSweeper`
  (`@Scheduled`, every 30 min) deletes temp files older than
  `browser.artifactRetention` (1h). **Durable MinIO/Blob storage is Phase 2B3.**
- **Browser credentials, later.** In production, a `FILL` value that is a secret
  (a login password, an API token typed into a form) will be authored as a
  `secretRef` — an indirection resolved by the Worker from a secret store at
  execution time — never as plaintext in the test definition or the snapshot.
  2B2 ships the plaintext `value` field only; `secretRef` resolution is a 2B3+
  follow-up and is called out here so the schema can add it additively.

### 7. Event schema evolution

- `TestCaseSnapshotItem` gains a nullable `BrowserTestSnapshot browserTest`
  (5-arg canonical ctor; the 3-arg and 4-arg convenience ctors are kept).
- `RunRequestedEvent.SCHEMA_VERSION → 3`; `RunCompletedEvent.SCHEMA_VERSION → 3`.
  `RunStartedEvent` / `RunFailedEvent` unchanged. `CaseResultSummary` unchanged —
  a browser case reports the same `verdict / durationMillis / firstFailureReason`
  shape as an API case.
- Backward compatibility: additive nullable nested field only; nothing renamed,
  moved or retyped; `FAIL_ON_UNKNOWN_PROPERTIES=false` ⇒ a v1/v2 event
  deserialises with `browserTest = null`. `spring.json.trusted.packages:
  com.qualityops.*` already covers `com.qualityops.events.Browser*` — **no Kafka
  config change**. `schemaVersion` stays advisory.

### 8. Authoring the browser spec (API side, minimal)

`test_cases` gains a nullable `browser_test` JSONB column
(`V10__add_test_cases_browser_test.sql`). `CreateTestCaseRequest` /
`UpdateTestCaseRequest` gain an optional `@Valid BrowserTestPayload` with bounds
(startUrl `@URL @Size(max=2048)`; 1–40 steps / assertions; bounded timeouts;
enum `@Pattern`s; `@AssertTrue` cross-field checks for selector / step /
assertion consistency) and a record-level `@AssertTrue` that `apiRequest` and
`browserTest` are mutually exclusive. `BrowserTestSpec` (module-local domain,
String-typed enums), `TestCase`, `TestCaseEntity`, `TestCaseRepositoryAdapter`,
`TestCaseService` and `TestCaseResponse` thread it through.
`RunService.trigger` freezes it into `config_snapshot` and onto
`RunRequestedEvent` via `toWireSnapshot` / `toWireBrowser`. No new endpoints —
`POST/PUT /api/v1/suites/{suiteId}/cases` and `/api/v1/cases/{id}` are reused.
The frontend authoring UI is out of scope this phase.

## Consequences

### Positive
- Real browser tests execute end to end through the existing
  API → Kafka → Worker → lifecycle/results path; the dashboard shows real
  per-case verdicts, durations and redacted failure reasons for UI tests.
- Zero new deployable units; the SSRF guard, redactor, dedup ledger and Kafka
  lifecycle are reused verbatim.
- Duplicate delivery — including after a Worker restart — does not re-run the
  scenario: the 2B1 claim ledger already covers it; the browser case is just
  another `ExecutionRunner`.
- Timeout / cancellation deterministically frees browser resources: `page` →
  `tracing` → `context` in a guarded `finally`, plus a `forceRecycle()` hard
  kill of the shared `Browser` on the future-timeout path.
- The seam (`PlaywrightBrowser` port) makes a later move to a standalone Node
  runner a swap of one adapter, with no event-contract or orchestration change.

### Negative
- The Worker runtime image switches to `mcr.microsoft.com/playwright/java`
  (glibc/jammy, **~2 GB**, not alpine) — bundled Chromium + OS libraries. Build
  and pull times grow; the build stage stays on the small Maven/alpine image.
- The Worker now manages browser subprocesses: a single-thread executor, a
  lazily-launched shared `Browser`, a watchdog kill path, a temp-artifact
  directory and a second `@Scheduled` sweeper.
- One more `apps/api` migration (V10) and a wider `TestCase` / `TestCaseEntity` /
  request / response signature, rippling through tests.
- `@Tag("browser")` ITs need a real Chromium; CI provisions it (cached
  `~/.cache/ms-playwright` + `playwright install-deps`) and a Maven
  `pre-integration-test` exec goal downloads the browser. Local `-DskipITs` or
  `-DexcludedGroups=browser` runs skip all of it.
- `RunRequestedEvent` / `RunCompletedEvent` re-serialise a slightly larger
  payload.

### Risks
- **Browser hang past the hard-kill grace.** `forceRecycle()` calls
  `Browser.close()` from the watchdog; if the driver process is wedged, the close
  can itself block. Mitigation: `future.cancel(true)` first, then recycle;
  `@PreDestroy` closes on shutdown; a stuck executor thread is visible in
  liveness. A bounded `forceRecycle` (route through the executor with `get(5s)`,
  null the refs on timeout) is the hardening follow-up.
- **~2 GB image** slows cold starts and image pulls; acceptable for a lab /
  single-tenant deploy, revisit for autoscaled production (a slimmer custom base
  with only Chromium deps).
- **Shared `Browser` cross-talk.** Contexts are isolated, but a crash in one
  scenario that corrupts the shared `Browser` would affect the next; `forceRecycle`
  on any `FAULT` / timeout bounds the blast radius to one following run at most.
- **DNS-rebinding TOCTOU** (as ADR-003) — Chromium is not IP-pinned; "all
  resolved addresses must pass" + sub-resource interception + disabled
  cross-origin surprises are the mitigation.
- **Redaction gaps** — text snippets are suppressed by default; `FILL` values are
  never emitted; screenshots may still contain rendered secrets, which is why
  they are temp-only and swept, and durable storage is deferred to 2B3 where
  access control is designed.
- **Rolling-deploy skew** — old API + new Worker ⇒ v2 events ⇒ no `browserTest`
  ⇒ API/simulated paths only; new API + old Worker ⇒ old Worker ignores
  `browserTest` ⇒ the case falls to simulated/API. Both safe; package name
  unchanged, so no DLT risk.

## Alternatives considered

- **B. Separate Node runner service.** Rejected for 2B2 as premature: a new
  deployable, a second implementation of SSRF / redaction / dedup / contracts,
  and a new Kafka group or HTTP contract, with no evidence yet that execution is
  browser-bound or that per-scenario process isolation is required. Documented
  above as the supported future swap behind the `PlaywrightBrowser` port.
- **`eclipse-temurin:21-jre-jammy` base + `RUN java -cp app.jar
  com.microsoft.playwright.CLI install --with-deps chromium`.** A viable
  fallback if the MS base image is ever unavailable or too large; produces a
  similar ~1.8 GB image with less pinning of the browser/OS-lib combination.
  Kept as a documented fallback, not the default.
- **A pool of pre-warmed `BrowserContext`s / one `Browser` per execution.**
  Rejected: a fresh `context` per run already gives isolation at a fraction of
  the cost of a fresh `Browser`; a context pool adds lifecycle complexity for no
  measured benefit at 2B2 volumes.
- **Cooperative mid-scenario cancellation.** Deferred with the rest of the
  cancel-signal source (ADR-003 §4, Phase 2D). 2B2's kill switch is
  `future.cancel(true)` + `forceRecycle()` on the hard-timeout path;
  `CancellationToken` is still checked once before submission.
- **Carrying screenshots/traces on `runs.completed`.** Rejected: the event stays
  small and metadata-only; durable artifact storage with real access control is
  the explicit subject of Phase 2B3.
- **A new `browser` Worker schema / table for scenario state.** Rejected:
  `worker.execution_attempt.runner_kind` is a plain `VARCHAR(16)` and `'BROWSER'`
  fits; the browser runner writes nothing else. ADR-003 §3's one-table reach is
  preserved.
