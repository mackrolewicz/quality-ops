package com.qualityops.api.execution.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.ListRunsUseCase;
import com.qualityops.api.execution.application.port.in.ProcessRunRequestedUseCase;
import com.qualityops.api.execution.application.port.in.TriggerRunUseCase;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;
import com.qualityops.api.execution.event.RunCompletedEvent;
import com.qualityops.api.execution.event.RunRequestedEvent;
import com.qualityops.api.execution.exception.RunNotFoundException;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class RunService implements TriggerRunUseCase, ListRunsUseCase, GetRunUseCase,
    GetRunStatsUseCase, ProcessRunRequestedUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final int PASS_RATE_PERCENT = 80;

    private final RunRepository runRepository;
    private final RunEventPublisher runEventPublisher;
    private final GetProjectUseCase getProjectUseCase;
    private final GetTestSuiteUseCase getTestSuiteUseCase;
    private final ListTestCasesForSuiteUseCase listTestCasesForSuiteUseCase;
    private final GetEnvironmentUseCase getEnvironmentUseCase;

    public RunService(RunRepository runRepository,
                       RunEventPublisher runEventPublisher,
                       GetProjectUseCase getProjectUseCase,
                       GetTestSuiteUseCase getTestSuiteUseCase,
                       ListTestCasesForSuiteUseCase listTestCasesForSuiteUseCase,
                       GetEnvironmentUseCase getEnvironmentUseCase) {
        this.runRepository = runRepository;
        this.runEventPublisher = runEventPublisher;
        this.getProjectUseCase = getProjectUseCase;
        this.getTestSuiteUseCase = getTestSuiteUseCase;
        this.listTestCasesForSuiteUseCase = listTestCasesForSuiteUseCase;
        this.getEnvironmentUseCase = getEnvironmentUseCase;
    }

    @Override
    public RunResponse trigger(CreateRunRequest request, UUID orgId, UUID triggeredBy) {
        var project = getProjectUseCase.getDomain(request.projectId(), orgId);

        var suite = getTestSuiteUseCase.getDomain(request.suiteId(), orgId);
        if (!suite.projectId().equals(project.id())) {
            throw new TestSuiteNotFoundException(
                "Test suite not found: " + request.suiteId() + " in project " + project.id());
        }

        var environment = getEnvironmentUseCase.getDomain(request.environmentId(), orgId);
        if (!environment.projectId().equals(project.id())) {
            throw new EnvironmentNotFoundException(
                "Environment not found: " + request.environmentId() + " in project " + project.id());
        }

        var cases = listTestCasesForSuiteUseCase.listAllForSuite(suite.id(), orgId);
        var snapshotItems = cases.stream()
            .map(c -> new TestCaseSnapshotItem(c.id(), c.name(), c.orderIndex()))
            .toList();
        var configSnapshot = new RunConfigSnapshot(snapshotItems);

        var now = Instant.now();
        var run = new TestRun(
            UUID.randomUUID(),
            orgId,
            project.id(),
            suite.id(),
            environment.id(),
            RunStatus.PENDING,
            triggeredBy,
            configSnapshot,
            null,
            null,
            now
        );
        var saved = runRepository.save(run);
        log.info("Triggered run {} for suite {} on environment {}", saved.id(), suite.id(), environment.id());

        runEventPublisher.publishRunRequested(new RunRequestedEvent(
            saved.id(), orgId, project.id(), suite.id(), environment.id(), triggeredBy, now));

        return RunResponse.from(saved);
    }

    @Override
    public PageResult<RunResponse> list(UUID orgId, UUID projectIdFilter, UUID suiteIdFilter,
                                         RunStatus statusFilter, int page, int size) {
        var result = runRepository.findAllByOrgId(orgId, projectIdFilter, suiteIdFilter, statusFilter, page, size);
        return new PageResult<>(
            result.items().stream().map(RunResponse::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @Override
    public RunResponse get(UUID id, UUID orgId) {
        return RunResponse.from(getDomain(id, orgId));
    }

    @Override
    public TestRun getDomain(UUID id, UUID orgId) {
        return runRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new RunNotFoundException("Run not found: " + id));
    }

    @Override
    public RunStats getStats(UUID projectId, UUID orgId, Instant since) {
        return runRepository.getStats(projectId, orgId, since);
    }

    @Override
    public void processRunRequested(RunRequestedEvent event) {
        boolean transitioned = runRepository.transitionStatus(
            event.runId(), RunStatus.PENDING, RunStatus.RUNNING, Instant.now());
        if (!transitioned) {
            log.warn("Run {} already processed or not in PENDING state — skipping duplicate/late delivery",
                event.runId());
            return;
        }

        if (!simulateExecution()) {
            return;
        }

        var resolvedStatus = ThreadLocalRandom.current().nextInt(100) < PASS_RATE_PERCENT
            ? RunStatus.PASSED
            : RunStatus.FAILED;

        boolean resolved = runRepository.transitionStatus(
            event.runId(), RunStatus.RUNNING, resolvedStatus, Instant.now());
        if (!resolved) {
            log.warn("Run {} was not in RUNNING state when attempting to resolve — skipping publish", event.runId());
            return;
        }

        // Re-read the run's frozen config snapshot (captured at trigger time)
        // so downstream consumers (result generation) act on what was
        // actually run, never on the suite's current live test case list.
        var run = runRepository.findByIdAndOrgId(event.runId(), event.orgId())
            .orElseThrow(() -> new RunNotFoundException("Run not found while completing: " + event.runId()));

        runEventPublisher.publishRunCompleted(new RunCompletedEvent(
            event.runId(), event.orgId(), event.projectId(), event.suiteId(), resolvedStatus, Instant.now(),
            run.configSnapshot().testCases()));
    }

    /**
     * Simulates test execution with a short blocking sleep. Safe on a
     * virtual thread. Returns {@code false} if interrupted, in which case
     * the caller should abandon processing without resolving the run.
     */
    private boolean simulateExecution() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(200, 500));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
