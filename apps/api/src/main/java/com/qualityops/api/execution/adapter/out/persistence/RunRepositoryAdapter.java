package com.qualityops.api.execution.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class RunRepositoryAdapter implements RunRepository {

    private final RunJpaRepository jpa;
    private final ObjectMapper objectMapper;

    RunRepositoryAdapter(RunJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public TestRun save(TestRun run) {
        var entity = RunEntity.create(
            run.id(),
            run.orgId(),
            run.projectId(),
            run.suiteId(),
            run.environmentId(),
            run.executionId(),
            run.status(),
            run.triggeredBy(),
            writeSnapshot(run.configSnapshot()),
            run.startedAt(),
            run.completedAt(),
            run.createdAt()
        );
        return toDomain(jpa.save(entity));
    }

    @Override
    public Optional<TestRun> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgId(id, orgId).map(this::toDomain);
    }

    @Override
    public PageResult<TestRun> findAllByOrgId(UUID orgId, UUID projectIdFilter, UUID suiteIdFilter,
                                               RunStatus statusFilter, QueueState queueStateFilter,
                                               int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByOrgId(orgId, projectIdFilter, suiteIdFilter, statusFilter,
            queueStateFilter, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(this::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    @Transactional
    public void transitionToCancelled(UUID runId, UUID orgId) {
        jpa.markPendingTerminal(runId, orgId, RunStatus.PENDING, RunStatus.CANCELLED, Instant.now());
    }

    @Override
    @Transactional
    public void transitionToFailed(UUID runId, UUID orgId) {
        jpa.markPendingTerminal(runId, orgId, RunStatus.PENDING, RunStatus.FAILED, Instant.now());
    }

    @Override
    public boolean transitionStatus(UUID runId, UUID orgId, UUID executionId,
                                    RunStatus fromStatus, RunStatus toStatus, Instant timestamp) {
        int updated = toStatus == RunStatus.RUNNING
            ? jpa.markRunning(runId, orgId, executionId, fromStatus, toStatus, timestamp)
            : jpa.markResolved(runId, orgId, executionId, fromStatus, toStatus, timestamp);
        return updated > 0;
    }

    @Override
    public boolean transitionToTerminal(UUID runId, UUID orgId, UUID executionId,
                                        RunStatus terminalStatus, Instant timestamp) {
        return jpa.markTerminal(runId, orgId, executionId, terminalStatus, timestamp,
            List.of(RunStatus.PENDING, RunStatus.RUNNING)) > 0;
    }

    @Override
    @Transactional
    public int reapToFailed(UUID runId, UUID orgId, Instant ts) {
        return jpa.markReapedFailed(runId, orgId, RunStatus.FAILED, ts,
            List.of(RunStatus.PENDING, RunStatus.RUNNING));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findConfigSnapshotJson(UUID runId, UUID orgId) {
        return jpa.findConfigSnapshotJson(runId, orgId);
    }

    @Override
    public TestRun saveRetryRun(RetryRunRow row) {
        var entity = RunEntity.create(
            row.id(),
            row.orgId(),
            row.projectId(),
            row.suiteId(),
            row.environmentId(),
            row.executionId(),
            RunStatus.PENDING,
            row.triggeredBy(),
            row.configSnapshotJson(), // raw verbatim — domain rule #2, no re-freeze
            null,
            null,
            row.createdAt()
        );
        return toDomain(jpa.save(entity));
    }

    @Override
    public RunStats getStats(UUID projectId, UUID orgId, Instant since) {
        var projection = jpa.getStats(orgId, projectId, since);
        long total = projection.getTotalRuns() == null ? 0L : projection.getTotalRuns();
        long passed = projection.getPassedRuns() == null ? 0L : projection.getPassedRuns();
        long failed = projection.getFailedRuns() == null ? 0L : projection.getFailedRuns();
        return new RunStats(total, passed, failed);
    }

    private TestRun toDomain(RunEntity entity) {
        return new TestRun(
            entity.getId(),
            entity.getOrgId(),
            entity.getProjectId(),
            entity.getSuiteId(),
            entity.getEnvironmentId(),
            entity.getExecutionId(),
            entity.getStatus(),
            entity.getTriggeredBy(),
            readSnapshot(entity.getConfigSnapshotJson()),
            entity.getStartedAt(),
            entity.getCompletedAt(),
            entity.getCreatedAt()
        );
    }

    private String writeSnapshot(RunConfigSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize run config snapshot", e);
        }
    }

    private RunConfigSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, RunConfigSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize run config snapshot", e);
        }
    }
}
