package com.qualityops.api.execution.dto;

import com.qualityops.api.execution.application.port.out.RepositoryRunRepository.RepositoryRunRow;
import com.qualityops.api.execution.domain.RepositoryRunState;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepositoryProvider;

import java.time.Instant;

/** ADR-009 §11 — additive-nullable block on {@code GET /api/v1/runs/{id}} for a
 *  run that carries a repository test case. Never present for a non-repo run. */
public record RepositoryRunResponse(
    RepositoryProvider provider,
    String repoPath,
    String requestedRef,
    String commitSha,
    RepoRefType refType,
    FrameworkPreset framework,
    RepositoryRunState state,
    String runnerImageDigest,
    Integer containerExitCode,
    Integer itemsTotal,
    Integer itemsPassed,
    Integer itemsFailed,
    Integer itemsSkipped,
    Instant checkoutAt,
    Instant startedAt,
    Instant finishedAt
) {
    public static RepositoryRunResponse from(RepositoryRunRow r) {
        return new RepositoryRunResponse(r.provider(), r.repoPath(), r.requestedRef(), r.commitSha(),
            r.refType(), r.framework(), r.state(), r.runnerImageDigest(), r.containerExitCode(),
            r.itemsTotal(), r.itemsPassed(), r.itemsFailed(), r.itemsSkipped(),
            r.checkoutAt(), r.startedAt(), r.finishedAt());
    }
}
