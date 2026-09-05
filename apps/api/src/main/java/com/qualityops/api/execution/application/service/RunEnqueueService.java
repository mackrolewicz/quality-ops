package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RepoTestSpec;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.RepositoryRunRequest;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolveRepositoryRunCommand;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolvedRepositoryRun;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RunRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The single admission point (ADR-006 §1.4). One transaction: validate ->
 *  freeze snapshot -> mint ids -> insert test_runs PENDING + run_queue QUEUED
 *  with the fully-serialised RunRequestedEvent frozen into requested_event_json.
 *  PUBLISHES NOTHING — the dispatcher does that later.
 *  <p>2F (ADR-009 §4): a case with a {@code repoTest} spec is preflighted (ref ->
 *  40-hex SHA + digest-pinned image, in the SCM module) <em>before</em>
 *  {@code test_runs} is inserted, then a frozen {@code repository_run} row is
 *  staged in the same transaction — any preflight failure rolls back everything. */
@Service
@Transactional
public class RunEnqueueService implements EnqueueRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunEnqueueService.class);

    private final RunRepository runRepository;
    private final RunQueueRepository runQueueRepository;
    private final GetProjectUseCase getProjectUseCase;
    private final GetTestSuiteUseCase getTestSuiteUseCase;
    private final ListTestCasesForSuiteUseCase listTestCasesForSuiteUseCase;
    private final GetEnvironmentUseCase getEnvironmentUseCase;
    private final RunEventMapper eventMapper;
    private final ResolveRepositoryRunUseCase resolveRepositoryRunUseCase;
    private final RepositoryRunWriteUseCase repositoryRunWriteUseCase;
    private final ObjectMapper objectMapper;

    public RunEnqueueService(RunRepository runRepository,
                             RunQueueRepository runQueueRepository,
                             GetProjectUseCase getProjectUseCase,
                             GetTestSuiteUseCase getTestSuiteUseCase,
                             ListTestCasesForSuiteUseCase listTestCasesForSuiteUseCase,
                             GetEnvironmentUseCase getEnvironmentUseCase,
                             RunEventMapper eventMapper,
                             ResolveRepositoryRunUseCase resolveRepositoryRunUseCase,
                             RepositoryRunWriteUseCase repositoryRunWriteUseCase,
                             ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.runQueueRepository = runQueueRepository;
        this.getProjectUseCase = getProjectUseCase;
        this.getTestSuiteUseCase = getTestSuiteUseCase;
        this.listTestCasesForSuiteUseCase = listTestCasesForSuiteUseCase;
        this.getEnvironmentUseCase = getEnvironmentUseCase;
        this.eventMapper = eventMapper;
        this.resolveRepositoryRunUseCase = resolveRepositoryRunUseCase;
        this.repositoryRunWriteUseCase = repositoryRunWriteUseCase;
        this.objectMapper = objectMapper;
    }

    @Override
    public EnqueueRunResult enqueue(EnqueueRunCommand cmd) {
        var project = getProjectUseCase.getDomain(cmd.projectId(), cmd.orgId());

        var suite = getTestSuiteUseCase.getDomain(cmd.suiteId(), cmd.orgId());
        if (!suite.projectId().equals(project.id())) {
            throw new TestSuiteNotFoundException(
                "Test suite not found: " + cmd.suiteId() + " in project " + project.id());
        }
        var environment = getEnvironmentUseCase.getDomain(cmd.environmentId(), cmd.orgId());
        if (!environment.projectId().equals(project.id())) {
            throw new EnvironmentNotFoundException(
                "Environment not found: " + cmd.environmentId() + " in project " + project.id());
        }

        var now = Instant.now();
        var executionId = UUID.randomUUID();
        var correlationId = UUID.randomUUID();

        var cases = listTestCasesForSuiteUseCase.listAllForSuite(suite.id(), cmd.orgId());
        var snapshotItems = cases.stream()
            .map(c -> new TestCaseSnapshotItem(c.id(), c.name(), c.orderIndex(),
                eventMapper.toSpec(c.apiRequest()), eventMapper.toBrowserSpec(c.browserTest()),
                eventMapper.toRepoSpec(c.repoTest())))
            .toList();

        long repoCases = snapshotItems.stream().filter(i -> i.repoTest() != null).count();
        if (repoCases > 0 && snapshotItems.size() > 1) {
            throw new IllegalArgumentException("REPOSITORY_CASE_MUST_BE_SOLE_CASE: a run whose suite "
                + "contains a repository test case may contain no other cases");
        }

        // Preflight every repo case BEFORE the run row exists (domain rule #2).
        // A RepositoryRefUnresolvableException / host-denied / credential-unresolved
        // here rolls back this whole @Transactional — no orphan test_runs/run_queue.
        Map<UUID, RepoTestSnapshot> frozenRepoByCase = new HashMap<>();
        Map<UUID, RepositoryRunFrozen> stagedRepoByCase = new LinkedHashMap<>();
        for (var item : snapshotItems) {
            if (item.repoTest() == null) {
                continue;
            }
            ResolvedRepositoryRun resolved = resolveRepositoryRunUseCase.resolve(
                new ResolveRepositoryRunCommand(cmd.orgId(), project.id(), toScmRequest(item.repoTest())));
            frozenRepoByCase.put(item.testCaseId(), eventMapper.toWireRepo(item.repoTest(), resolved));
            stagedRepoByCase.put(item.testCaseId(), resolved.stagedRow());
        }

        var configSnapshot = new RunConfigSnapshot(snapshotItems);
        var run = new TestRun(UUID.randomUUID(), cmd.orgId(), project.id(), suite.id(),
            environment.id(), executionId, RunStatus.PENDING, cmd.triggeredBy(),
            configSnapshot, null, null, now);
        var saved = runRepository.save(run);

        stagedRepoByCase.values().forEach(frozen ->
            repositoryRunWriteUseCase.stageFrozen(saved.id(), cmd.orgId(), frozen));

        var event = eventMapper.toRequestedEvent(cmd.orgId(), saved.id(), executionId, correlationId,
            now, project.id(), suite.id(), environment.id(), cmd.triggeredBy(), snapshotItems,
            frozenRepoByCase);

        runQueueRepository.enqueue(new RunQueueRepository.EnqueueRow(
            cmd.orgId(), saved.id(), cmd.scheduleId(), cmd.priority(), serialise(event), now));

        log.info("Enqueued run {} (execution {}, source {}, priority {}, repoCases {}) — not yet published",
            saved.id(), executionId, cmd.source(), cmd.priority(), repoCases);
        return new EnqueueRunResult(saved.id(), executionId, QueueState.QUEUED);
    }

    @Override
    public EnqueueRunResult enqueueRetry(UUID originalRunId, UUID orgId) {
        var original = runRepository.findByIdAndOrgId(originalRunId, orgId)
            .orElseThrow(() -> new IllegalStateException("retry source run missing: " + originalRunId));
        String snapshotJson = runRepository.findConfigSnapshotJson(originalRunId, orgId)
            .orElseThrow(() -> new IllegalStateException(
                "retry source config snapshot missing: " + originalRunId));
        var origQ = runQueueRepository.findByRunIdAndOrgId(originalRunId, orgId)
            .orElseThrow(() -> new IllegalStateException(
                "retry source run_queue row missing: " + originalRunId));
        // Non-null: enqueueRetry runs BEFORE the queue terminal write that nulls
        // requested_event_json (RunLifecycleService ordering, ADR-007 §2.2).
        var origEvent = deserialise(origQ.requestedEventJson());

        var now = Instant.now();
        var newExecutionId = UUID.randomUUID();

        // Use the id the repository actually assigned (RunEntity has a @GeneratedValue
        // id, so save() may mint a fresh one) — mirrors RunEnqueueService.enqueue,
        // which builds the queue row from saved.id(), not the pre-generated id.
        var savedRetry = runRepository.saveRetryRun(new RunRepository.RetryRunRow(UUID.randomUUID(),
            orgId, original.projectId(), original.suiteId(), original.environmentId(), newExecutionId,
            original.triggeredBy(), snapshotJson, now));
        var newRunId = savedRetry.id();

        var retryEvent = new RunRequestedEvent(UUID.randomUUID(), origEvent.correlationId(), orgId,
            newRunId, newExecutionId, now, origEvent.schemaVersion(), origEvent.projectId(),
            origEvent.suiteId(), origEvent.environmentId(), origEvent.triggeredBy(), origEvent.testCases());

        // ADR-009 §4 — replay the ALREADY-frozen snapshot's SHA/image (no re-preflight,
        // no re-freeze); stage a fresh repository_run row from it.
        origEvent.testCases().stream()
            .filter(c -> c.repoTest() != null)
            .forEach(c -> repositoryRunWriteUseCase.stageFrozen(newRunId, orgId,
                RepositoryRunFrozen.fromSnapshot(c.repoTest())));

        runQueueRepository.enqueueRetry(new RunQueueRepository.EnqueueRetryRow(orgId, newRunId,
            origQ.scheduleId(), origQ.priority(), serialise(retryEvent), now,
            originalRunId, origQ.retryCount() + 1));

        log.info("Enqueued retry run {} (execution {}) of {} — not yet published",
            newRunId, newExecutionId, originalRunId);
        return new EnqueueRunResult(newRunId, newExecutionId, QueueState.QUEUED);
    }

    private static RepositoryRunRequest toScmRequest(RepoTestSpec spec) {
        var envVars = spec.environmentVars() == null ? java.util.List.<RepositoryRunRequest.EnvVarValue>of()
            : spec.environmentVars().stream()
                .map(e -> new RepositoryRunRequest.EnvVarValue(e.name(), e.value()))
                .toList();
        var secretRefs = spec.secretVars() == null
            ? java.util.List.<RepositoryRunRequest.SecretRefValue>of()
            : spec.secretVars().stream()
                .map(s -> new RepositoryRunRequest.SecretRefValue(s.name(), s.secretRef()))
                .toList();
        return new RepositoryRunRequest(spec.repositoryConnectionId(), spec.requestedRef(),
            spec.framework(), spec.workingDir(), spec.command(), spec.reportFormat(),
            spec.reportPaths(), spec.artifactGlobs(), envVars, secretRefs,
            spec.resourceProfile(), spec.networkPolicy(), spec.timeoutSeconds());
    }

    private RunRequestedEvent deserialise(String json) {
        try {
            return objectMapper.readValue(json, RunRequestedEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt frozen event for retry", e);
        }
    }

    private String serialise(RunRequestedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise RunRequestedEvent for the queue", e);
        }
    }
}
