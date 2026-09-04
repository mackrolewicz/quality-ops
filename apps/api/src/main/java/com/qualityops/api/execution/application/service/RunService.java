package com.qualityops.api.execution.application.service;

import com.qualityops.api.audit.annotation.Timed;
import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.ListRunsUseCase;
import com.qualityops.api.execution.application.port.in.TriggerRunUseCase;
import com.qualityops.api.execution.application.port.out.RepositoryRunRepository;
import com.qualityops.api.execution.application.port.out.RepositoryRunRepository.RepositoryRunRow;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.dto.RepositoryRunResponse;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;
import com.qualityops.api.execution.exception.RunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class RunService implements TriggerRunUseCase, ListRunsUseCase, GetRunUseCase,
    GetRunStatsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunRepository runRepository;
    private final EnqueueRunUseCase enqueueRunUseCase;
    private final RunQueueRepository runQueueRepository;
    private final RepositoryRunRepository repositoryRunRepository;

    public RunService(RunRepository runRepository,
                      EnqueueRunUseCase enqueueRunUseCase,
                      RunQueueRepository runQueueRepository,
                      RepositoryRunRepository repositoryRunRepository) {
        this.runRepository = runRepository;
        this.enqueueRunUseCase = enqueueRunUseCase;
        this.runQueueRepository = runQueueRepository;
        this.repositoryRunRepository = repositoryRunRepository;
    }

    @Override
    @Timed("run.trigger")
    public RunResponse trigger(CreateRunRequest request, UUID orgId, UUID triggeredBy) {
        var priority = RunPriority.fromNullable(request.priority());
        var result = enqueueRunUseCase.enqueue(new EnqueueRunUseCase.EnqueueRunCommand(
            orgId, request.projectId(), request.suiteId(), request.environmentId(),
            triggeredBy, priority, RunSource.MANUAL, null));
        var run = runRepository.findByIdAndOrgId(result.runId(), orgId).orElseThrow();
        log.info("Run {} enqueued via manual trigger (priority {})", result.runId(), priority);
        return RunResponse.from(run, result.queueState(), priority, Boolean.FALSE);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "runs.list", key = "#orgId + ':' + #projectIdFilter + ':' + #suiteIdFilter "
        + "+ ':' + #statusFilter + ':' + #queueStateFilter + ':' + #page + ':' + #size")
    public PageResult<RunResponse> list(UUID orgId, UUID projectIdFilter, UUID suiteIdFilter,
                                        RunStatus statusFilter, QueueState queueStateFilter,
                                        int page, int size) {
        var result = runRepository.findAllByOrgId(
            orgId, projectIdFilter, suiteIdFilter, statusFilter, queueStateFilter, page, size);
        var runIds = result.items().stream().map(TestRun::id).toList();
        var byRun = runQueueRepository.findSummariesByRunIds(orgId, runIds);
        var repoByRun = repositoryRunRepository.findByRunIdsAndOrgId(orgId, runIds);
        var items = result.items().stream().map(run -> {
            var q = byRun.get(run.id());
            var repo = repoResponse(repoByRun.get(run.id()));
            return q == null
                ? RunResponse.from(run, null, null, null, null, null, repo)
                : RunResponse.from(run, q.queueState(), q.priority(), q.cancelRequested(),
                    q.retryOf(), q.retryCount(), repo);
        }).toList();
        return new PageResult<>(items, result.page(), result.size(), result.total());
    }

    @Override
    public RunResponse get(UUID id, UUID orgId) {
        var run = getDomain(id, orgId);
        var q = runQueueRepository.findByRunIdAndOrgId(id, orgId).orElse(null);
        var repo = repoResponse(repositoryRunRepository.findByRunIdAndOrgId(id, orgId).orElse(null));
        return q == null
            ? RunResponse.from(run, null, null, null, null, null, repo)
            : RunResponse.from(run, q.queueState(), q.priority(), q.cancelRequested(),
                q.retryOf(), q.retryCount(), repo);
    }

    private static RepositoryRunResponse repoResponse(RepositoryRunRow row) {
        return row == null ? null : RepositoryRunResponse.from(row);
    }

    // noRollbackFor: a "not found" lookup is normal control flow for every
    // caller that catches RunNotFoundException (e.g. ResultService.guardRun) -
    // without this, the class-level @Transactional default marks the CALLER's
    // enclosing (joined, REQUIRED-propagation) transaction rollback-only the
    // moment this method throws, even though the caller's own catch block
    // never sees that the transaction is already doomed. On a Kafka listener
    // this silently discards the whole message (UnexpectedRollbackException on
    // commit) and, on a single-partition topic with no retry ceiling, blocks
    // every later message behind it forever.
    @Override
    @Transactional(readOnly = true, noRollbackFor = RunNotFoundException.class)
    public TestRun getDomain(UUID id, UUID orgId) {
        return runRepository.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> new RunNotFoundException("Run not found: " + id));
    }

    @Override
    public RunStats getStats(UUID projectId, UUID orgId, Instant since) {
        return runRepository.getStats(projectId, orgId, since);
    }
}
