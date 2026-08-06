package com.qualityops.api.execution.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
                                               RunStatus statusFilter, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByOrgId(orgId, projectIdFilter, suiteIdFilter, statusFilter,
            PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(this::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    public boolean transitionStatus(UUID runId, RunStatus fromStatus, RunStatus toStatus, Instant timestamp) {
        int updated = toStatus == RunStatus.RUNNING
            ? jpa.markRunning(runId, fromStatus, toStatus, timestamp)
            : jpa.markResolved(runId, fromStatus, toStatus, timestamp);
        return updated > 0;
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
