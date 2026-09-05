package com.qualityops.api.execution.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.execution.application.port.out.RepositoryRunRepository;
import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
class RepositoryRunRepositoryAdapter implements RepositoryRunRepository {

    private final RepositoryRunJpaRepository jpa;
    private final ObjectMapper objectMapper;

    RepositoryRunRepositoryAdapter(RepositoryRunJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void insertFrozen(UUID runId, UUID orgId, RepositoryRunFrozen f) {
        jpa.insertFrozen(orgId, runId, f.repositoryConnectionId(), f.provider().name(), f.repoHost(),
            f.repoPath(), f.requestedRef(), f.commitSha(), f.refType().name(), f.framework().name(),
            f.runnerImageRef(), f.workingDir(), writeJson(f.command()), f.reportFormat().name(),
            writeJson(f.reportPaths()), writeJson(f.artifactGlobs()), f.resourceProfile().name(),
            f.networkPolicy().name(), f.timeoutSeconds());
    }

    @Override
    @Transactional
    public int transitionState(UUID runId, UUID orgId, UUID executionId,
                               List<RepositoryRunState> fromStates, RepositoryRunState toState) {
        return jpa.transitionState(runId, orgId, executionId,
            fromStates.stream().map(Enum::name).toList(), toState.name());
    }

    private static final int MAX_ERROR_DETAIL_CHARS = 1000; // matches repository_run.error_detail VARCHAR(1000)

    @Override
    @Transactional
    public int applyTelemetry(UUID runId, UUID orgId, UUID executionId, String imageDigest, Integer exitCode,
                              Integer itemsTotal, Integer itemsPassed, Integer itemsFailed,
                              Integer itemsSkipped, Instant checkoutAt, Instant startedAt, Instant finishedAt,
                              String errorDetail, int attemptEpoch) {
        return jpa.applyTelemetry(runId, orgId, executionId, imageDigest, exitCode, itemsTotal, itemsPassed,
            itemsFailed, itemsSkipped, checkoutAt, startedAt, finishedAt, truncate(errorDetail), attemptEpoch);
    }

    private static String truncate(String errorDetail) {
        if (errorDetail == null || errorDetail.length() <= MAX_ERROR_DETAIL_CHARS) {
            return errorDetail;
        }
        return errorDetail.substring(0, MAX_ERROR_DETAIL_CHARS);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryRunRow> findByRunIdAndOrgId(UUID runId, UUID orgId) {
        return jpa.findByRunIdAndOrgId(runId, orgId).map(RepositoryRunRepositoryAdapter::toRow);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, RepositoryRunRow> findByRunIdsAndOrgId(UUID orgId, List<UUID> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        return jpa.findByOrgIdAndRunIdIn(orgId, runIds).stream()
            .map(RepositoryRunRepositoryAdapter::toRow)
            .collect(Collectors.toMap(RepositoryRunRow::runId, Function.identity()));
    }

    private static RepositoryRunRow toRow(RepositoryRunEntity e) {
        return new RepositoryRunRow(e.getRunId(), e.getOrgId(), e.getProvider(), e.getRepoHost(),
            e.getRepoPath(), e.getRequestedRef(), e.getCommitSha(), e.getRefType(), e.getFrameworkPreset(),
            e.getRunnerImageRef(), e.getResourceProfile(), e.getNetworkPolicy(), e.getTimeoutSeconds(),
            e.getState(), e.getRunnerImageDigest(), e.getContainerExitCode(), e.getItemsTotal(),
            e.getItemsPassed(), e.getItemsFailed(), e.getItemsSkipped(), e.getCheckoutAt(),
            e.getStartedAt(), e.getFinishedAt(), e.getErrorDetail());
    }

    private String writeJson(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise repository_run JSON column", e);
        }
    }
}
