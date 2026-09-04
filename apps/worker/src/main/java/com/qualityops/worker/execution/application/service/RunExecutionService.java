package com.qualityops.worker.execution.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ArtifactReference;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.RepositoryTestItem;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.RunStartedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.CancellationRegistry;
import com.qualityops.worker.execution.application.port.in.ProcessRunRequestedUseCase;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.AlreadyCompleted;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.AlreadyRunning;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.Claimed;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.ClaimResult;
import com.qualityops.worker.execution.application.port.out.RunLifecyclePublisher;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RunExecutionService implements ProcessRunRequestedUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);
    private static final String COMPLETED = "runs.completed";
    private static final String FAILED = "runs.failed";
    private static final int HEARTBEAT_EVERY_N_CASES = 5;

    private final RunLifecyclePublisher publisher;
    private final ExecutionAttemptStore store;
    private final ExecutionRunnerResolver resolver;
    private final WorkerExecutionProperties props;
    private final ObjectMapper objectMapper;
    private final ArtifactUploadService artifactUploadService;
    private final Sleeper sleeper;
    private final CancellationRegistry cancellationRegistry;
    private final ObjectProvider<RepoExecWorkerProperties> repoExecPropsProvider;

    public RunExecutionService(RunLifecyclePublisher publisher, ExecutionAttemptStore store,
                               ExecutionRunnerResolver resolver, WorkerExecutionProperties props,
                               ObjectMapper objectMapper, ArtifactUploadService artifactUploadService,
                               Sleeper sleeper, CancellationRegistry cancellationRegistry,
                               ObjectProvider<RepoExecWorkerProperties> repoExecPropsProvider) {
        this.publisher = publisher;
        this.store = store;
        this.resolver = resolver;
        this.props = props;
        this.objectMapper = objectMapper;
        this.artifactUploadService = artifactUploadService;
        this.sleeper = sleeper;
        this.cancellationRegistry = cancellationRegistry;
        this.repoExecPropsProvider = repoExecPropsProvider;
    }

    @Override
    public void processRunRequested(RunRequestedEvent event) {
        var kind = resolver.resolvedKindFor(event.testCases());
        // claim() / markCompleted() throwing = worker-DB unreachable -> propagate -> Kafka retry -> DLT.
        ClaimResult claim = store.claim(event.executionId(), event.runId(), event.orgId(), kind);

        int epoch;
        if (claim instanceof AlreadyCompleted ac) {
            publisher.republishTerminal(ac.terminalTopic(), event.runId(), ac.terminalEventJson());
            return;
        } else if (claim instanceof AlreadyRunning ar) {
            var stolen = store.steal(event.executionId(), props.claimLease());
            if (stolen.isEmpty()) {
                log.info("Attempt {} still RUNNING under an unexpired lease — skipping", event.executionId());
                return;
            }
            epoch = stolen.getAsInt();
        } else {
            epoch = ((Claimed) claim).epoch();
        }

        // Pre-start cancel check (ADR-006 §5.4): a runs.cancel consumed before this
        // runs.requested. Still claim (done above) so the attempt owns a cached
        // terminal for dedup, then fail deterministically — never silently drop.
        if (cancellationRegistry.isCancelled(event.executionId())) {
            emitFailed(event, epoch, "execution cancelled before start");
            log.info("Run {} was cancelled before start — claimed then failed", event.runId());
            return;
        }

        publisher.publishRunStarted(startedEvent(event));

        CancellationToken token = () -> cancellationRegistry.isCancelled(event.executionId());
        var started = Instant.now();
        try {
            var outcomes = runCases(event, epoch, token, started);
            var aggregate = aggregate(outcomes);
            var completed = completedEvent(event, aggregate, outcomes);
            if (!store.markCompleted(event.executionId(), epoch, COMPLETED, json(completed))) {
                log.warn("Attempt {} stolen mid-run — not publishing runs.completed", event.executionId());
                return;
            }
            publisher.publishRunCompleted(completed);
            log.info("Run {} completed {} ({} cases)", event.runId(), aggregate, outcomes.size());
        } catch (ExecutionHarnessException e) {
            emitFailed(event, epoch, reasonFor(e));
        }
    }

    private List<CaseOutcome> runCases(RunRequestedEvent event, int epoch,
                                       CancellationToken token, Instant started) {
        var budget = props.runWallClockBudget();
        var outcomes = new ArrayList<CaseOutcome>(event.testCases().size());
        int i = 0;
        for (TestCaseSnapshotItem c : event.testCases()) {
            if (i % HEARTBEAT_EVERY_N_CASES == 0) {
                store.heartbeat(event.executionId(), epoch);
            }

            CaseExecutionResult result;
            if (token.isCancelled()) {
                result = errorResult(c, "run cancelled");
            } else if (Duration.between(started, Instant.now()).compareTo(budget) > 0) {
                result = errorResult(c, "run wall-clock budget exceeded");
            } else {
                result = executeWithRetry(event, c, token, started, budget);   // may throw ExecutionHarnessException
            }

            boolean secretCase = usesSecretRef(c);
            List<ArtifactReference> artifacts =
                artifactUploadService.uploadForCase(event, result, secretCase);
            publishChunk(event, result, artifacts);
            outcomes.add(new CaseOutcome(result, artifacts));
            i++;
        }
        return outcomes;
    }

    /** Bounded in-run retry: re-invoke the SAME resolved runner for a transient
     *  {@code TIMEOUT}/{@code ERROR} with no observed side effect, while the run
     *  budget still has room for another {@code effectiveTimeout}. No scheduler,
     *  no queue, no re-published request. */
    private CaseExecutionResult executeWithRetry(RunRequestedEvent event, TestCaseSnapshotItem c,
                                                 CancellationToken token, Instant started, Duration budget) {
        var retry = props.retry();
        var runner = resolver.resolve(c);
        int attempt = 0;
        CaseExecutionResult result;
        while (true) {
            var ctx = new CaseExecutionContext(event.runId(), event.orgId(), event.executionId(),
                event.environmentId(), c, effectiveTimeoutFor(c), resolveMaxBytes(c), token, attempt);
            result = runner.execute(ctx);

            if (retry == null || !retry.enabled()) {
                return result;
            }
            if (attempt + 1 >= retry.maxAttempts()) {
                return result;
            }
            if (!retry.isRetryable(result.status().name())
                || result.sideEffectClass() != SideEffectClass.NONE_OBSERVED) {
                return result;
            }
            var elapsed = Duration.between(started, Instant.now());
            if (elapsed.plus(effectiveTimeoutFor(c)).compareTo(budget) > 0) {
                log.info("Case {} ended {} but no run-budget room for a retry — keeping attempt {}",
                    c.testCaseId(), result.status(), attempt);
                return result;
            }
            attempt++;
            log.info("Retrying case {} after {} (attempt {} of {})",
                c.testCaseId(), result.status(), attempt, retry.maxAttempts() - 1);
            sleepBackoff(retry.backoff());
        }
    }

    private void sleepBackoff(Duration backoff) {
        if (backoff == null || backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionHarnessException("retry backoff interrupted", e);
        }
    }

    private void publishChunk(RunRequestedEvent event, CaseExecutionResult r, List<ArtifactReference> artifacts) {
        try {
            List<RepositoryTestItem> repositoryItems =
                r.repository() == null ? List.of() : r.repository().items();
            RepositoryRunProvenance provenance = r.repository() == null ? null : r.repository().provenance();
            publisher.publishResultChunk(new ResultChunkEvent(
                UUID.randomUUID(), event.correlationId(), event.orgId(), event.runId(), event.executionId(),
                Instant.now(), ResultChunkEvent.SCHEMA_VERSION,
                r.testCaseId(), r.attemptEpoch(), toVerdict(r.status()), r.duration().toMillis(),
                r.reason(), artifacts, repositoryItems, provenance));
        } catch (RuntimeException e) {
            log.warn("Failed to publish results.chunk for run {} case {} — continuing ({})",
                event.runId(), r.testCaseId(), e.getClass().getSimpleName());
        }
    }

    /** ADR-009 §8 — a repository case that resolved any {@code secretVars} or
     *  carries a non-null {@code credentialRef} drives the secret-run artifact
     *  gate + the per-execution redaction mask set, same as an API/browser case
     *  with a {@code secretRef}. */
    private static boolean usesSecretRef(TestCaseSnapshotItem c) {
        if (c.apiRequest() != null && c.apiRequest().headers() != null
            && c.apiRequest().headers().stream().anyMatch(h -> h.secretRef() != null)) {
            return true;
        }
        if (c.browserTest() != null && c.browserTest().steps() != null
            && c.browserTest().steps().stream().anyMatch(s -> s.secretValue() != null)) {
            return true;
        }
        if (c.repoTest() != null) {
            boolean hasSecretVars = c.repoTest().secretVars() != null && !c.repoTest().secretVars().isEmpty();
            return hasSecretVars || c.repoTest().credentialRef() != null;
        }
        return false;
    }

    private Duration effectiveTimeoutFor(TestCaseSnapshotItem c) {
        if (c.repoTest() != null) {
            RepoExecWorkerProperties repoProps = repoExecPropsProvider.getIfAvailable();
            Duration requested = Duration.ofSeconds(Math.max(1, c.repoTest().timeoutSeconds()));
            Duration max = repoProps != null && repoProps.maxRunTimeout() != null
                ? repoProps.maxRunTimeout() : Duration.ofMinutes(30);
            return requested.compareTo(max) > 0 ? max : requested;
        }
        if (c.browserTest() != null) {
            return props.browser().effectiveTestTimeout(c.browserTest().testTimeoutMillis());
        }
        return props.effectiveTimeout(c.apiRequest() != null ? c.apiRequest().timeoutMillis() : null);
    }

    private long resolveMaxBytes(TestCaseSnapshotItem c) {
        Long perCase = c.apiRequest() != null ? c.apiRequest().maxResponseBytes() : null;
        return perCase != null ? Math.min(perCase, props.maxResponseBytes()) : props.maxResponseBytes();
    }

    private void emitFailed(RunRequestedEvent event, int epoch, String reason) {
        var failed = failedEvent(event, reason);
        if (store.markCompleted(event.executionId(), epoch, FAILED, json(failed))) {
            publisher.publishRunFailed(failed);
        }
    }

    private static String reasonFor(ExecutionHarnessException e) {
        return e.getMessage() != null && e.getMessage().contains("interrupted")
            ? "Execution interrupted" : "Execution harness error";   // generic, redaction-safe
    }

    private static RunOutcome aggregate(List<CaseOutcome> outcomes) {
        return outcomes.stream().allMatch(o -> o.result().status() == CaseStatus.PASSED)
            ? RunOutcome.PASSED : RunOutcome.FAILED;
    }

    private CaseExecutionResult errorResult(TestCaseSnapshotItem c, String reason) {
        return new CaseExecutionResult(c.testCaseId(), c.name(), c.orderIndex(),
            CaseStatus.ERROR, Duration.ZERO, null, null, List.of(), reason, null,
            SideEffectClass.NONE_OBSERVED, 0);
    }

    private String json(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new ExecutionHarnessException("serialise terminal event", e);
        }
    }

    private static RunStartedEvent startedEvent(RunRequestedEvent r) {
        return new RunStartedEvent(UUID.randomUUID(), r.correlationId(), r.orgId(), r.runId(),
            r.executionId(), Instant.now(), RunStartedEvent.SCHEMA_VERSION);
    }

    private static RunCompletedEvent completedEvent(RunRequestedEvent r, RunOutcome outcome,
                                                    List<CaseOutcome> outcomes) {
        var summaries = outcomes.stream().map(RunExecutionService::toSummary).toList();
        return new RunCompletedEvent(UUID.randomUUID(), r.correlationId(), r.orgId(), r.runId(),
            r.executionId(), Instant.now(), RunCompletedEvent.SCHEMA_VERSION,
            r.projectId(), r.suiteId(), outcome, r.testCases(), summaries);
    }

    private static CaseResultSummary toSummary(CaseOutcome o) {
        var c = o.result();
        List<RepositoryTestItem> repositoryItems = c.repository() == null ? List.of() : c.repository().items();
        RepositoryRunProvenance provenance = c.repository() == null ? null : c.repository().provenance();
        return new CaseResultSummary(c.testCaseId(), toVerdict(c.status()), c.duration().toMillis(),
            c.reason(), c.attemptEpoch(), o.artifacts(), repositoryItems, provenance);
    }

    private static CaseResultSummary.Verdict toVerdict(CaseStatus s) {
        return switch (s) {
            case PASSED -> CaseResultSummary.Verdict.PASSED;
            case FAILED -> CaseResultSummary.Verdict.FAILED;
            case TIMEOUT -> CaseResultSummary.Verdict.TIMEOUT;
            case BLOCKED -> CaseResultSummary.Verdict.BLOCKED;
            case ERROR -> CaseResultSummary.Verdict.ERROR;
        };
    }

    private static RunFailedEvent failedEvent(RunRequestedEvent r, String reason) {
        return new RunFailedEvent(UUID.randomUUID(), r.correlationId(), r.orgId(), r.runId(),
            r.executionId(), Instant.now(), RunFailedEvent.SCHEMA_VERSION, reason);
    }

    /** One case's final result plus its attempted artifact references. */
    private record CaseOutcome(CaseExecutionResult result, List<ArtifactReference> artifacts) {}
}
