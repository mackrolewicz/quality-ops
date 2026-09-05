package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class RunQueueRepositoryAdapter implements RunQueueRepository {

    private final RunQueueJpaRepository jpa;

    RunQueueRepositoryAdapter(RunQueueJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void enqueue(EnqueueRow row) {
        jpa.save(RunQueueEntity.create(UUID.randomUUID(), row.orgId(), row.runId(), row.scheduleId(),
            row.priority(), QueueState.QUEUED, row.requestedEventJson(), row.enqueuedAt(), 0, false, null));
    }

    @Override
    @Transactional
    public void enqueueRetry(EnqueueRetryRow row) {
        jpa.save(RunQueueEntity.createRetry(UUID.randomUUID(), row.orgId(), row.runId(), row.scheduleId(),
            row.priority(), QueueState.QUEUED, row.requestedEventJson(), row.enqueuedAt(),
            row.retryOf(), row.retryCount()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueueRow> findByRunIdAndOrgId(UUID runId, UUID orgId) {
        return jpa.findByRunIdAndOrgId(runId, orgId).map(RunQueueRepositoryAdapter::toRow);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, QueueSummary> findSummariesByRunIds(UUID orgId, Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, QueueSummary> out = new HashMap<>();
        for (RunQueueEntity e : jpa.findByRunIdInAndOrgId(runIds, orgId)) {
            out.put(e.getRunId(), new QueueSummary(e.getQueueState(), e.getPriority(),
                e.isCancelRequested(), e.getRetryOf(), e.getRetryCount()));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countActivePerOrg() {
        Map<UUID, Integer> out = new HashMap<>();
        for (Object[] r : jpa.countActivePerOrg(QueueState.ACTIVE)) {
            out.put((UUID) r[0], ((Number) r[1]).intValue());
        }
        return out;
    }

    @Override
    @Transactional // FOR UPDATE SKIP LOCKED — must run in a read-write transaction
    public List<DispatchCandidate> selectQueuedCandidates(int batch, int agingStepSeconds, int agingMaxBoost) {
        return jpa.selectQueuedCandidates(batch, agingStepSeconds, agingMaxBoost).stream()
            .map(RunQueueRepositoryAdapter::toCandidate)
            .toList();
    }

    @Override
    @Transactional
    public boolean claimForDispatch(UUID runId) {
        return jpa.claimForDispatch(runId) > 0;
    }

    @Override
    @Transactional
    public RollbackOutcome rollbackDispatch(UUID runId) {
        int rows = jpa.rollbackDispatch(runId);
        if (rows == 0) {
            return RollbackOutcome.NOOP;
        }
        return jpa.findQueueStateByRunId(runId).filter(s -> s == QueueState.CANCELLED).isPresent()
            ? RollbackOutcome.CANCELLED
            : RollbackOutcome.REQUEUED;
    }

    @Override
    @Transactional
    public void markDispatchFailed(UUID runId) {
        jpa.markDispatchFailed(runId);
    }

    @Override
    @Transactional // FOR UPDATE SKIP LOCKED — read-write transaction
    public List<DispatchCandidate> selectStrandedDispatched(Instant graceCutoff, Instant timeoutCutoff, int batch) {
        return jpa.selectStrandedDispatched(graceCutoff, timeoutCutoff, batch).stream()
            .map(RunQueueRepositoryAdapter::toCandidate)
            .toList();
    }

    @Override
    @Transactional
    public boolean reclaimStranded(UUID runId, Instant graceCutoff) {
        return jpa.reclaimStranded(runId, graceCutoff) > 0;
    }

    @Override
    @Transactional
    public boolean reclaimStrandedCancel(UUID runId, Instant graceCutoff) {
        return jpa.reclaimStrandedCancel(runId, graceCutoff) > 0;
    }

    @Override
    @Transactional // FOR UPDATE SKIP LOCKED — read-write transaction
    public List<StuckRun> selectStuckActive(Instant timeoutCutoff, int batch) {
        return jpa.selectStuckActive(timeoutCutoff, batch).stream()
            .map(r -> new StuckRun(toUuid(r[0]), toUuid(r[1]), QueueState.valueOf((String) r[2])))
            .toList();
    }

    @Override
    @Transactional
    public boolean transitionQueueState(UUID runId, UUID orgId, Set<QueueState> from,
                                        QueueState to, boolean terminal) {
        List<String> fromNames = from.stream().map(Enum::name).toList();
        return jpa.transitionQueueState(runId, orgId, fromNames, to.name(), terminal) > 0;
    }

    @Override
    @Transactional
    public boolean cancelQueued(UUID runId, UUID orgId) {
        return jpa.cancelQueued(runId, orgId) > 0;
    }

    @Override
    @Transactional
    public boolean requestCancel(UUID runId, UUID orgId) {
        return jpa.requestCancel(runId, orgId) > 0;
    }

    @Override
    @Transactional
    public int deleteTerminalOlderThan(Instant cutoff) {
        return jpa.deleteTerminalOlderThan(cutoff);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<RunPriority, Long> queueDepthByPriority() {
        Map<RunPriority, Long> out = new HashMap<>();
        for (Object[] r : jpa.queueDepthByState(QueueState.QUEUED)) {
            out.put((RunPriority) r[0], ((Number) r[1]).longValue());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> oldestQueuedEnqueuedAt() {
        return Optional.ofNullable(jpa.oldestEnqueuedAt(QueueState.QUEUED));
    }

    @Override
    @Transactional(readOnly = true)
    public long activeRunCount() {
        return jpa.countByStates(QueueState.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public long countRecentRetriesForOrg(UUID orgId, Instant since) {
        return jpa.countRecentRetriesForOrg(orgId, since);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<RunPriority, Long> queueDepthByPriorityForOrg(UUID orgId) {
        Map<RunPriority, Long> out = new HashMap<>();
        for (Object[] r : jpa.queueDepthByStateForOrg(QueueState.QUEUED, orgId)) {
            out.put((RunPriority) r[0], ((Number) r[1]).longValue());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> oldestQueuedEnqueuedAtForOrg(UUID orgId) {
        return Optional.ofNullable(jpa.oldestEnqueuedAtForOrg(QueueState.QUEUED, orgId));
    }

    @Override
    @Transactional(readOnly = true)
    public long activeRunCountForOrg(UUID orgId) {
        return jpa.countByStatesForOrg(QueueState.ACTIVE, orgId);
    }

    private static DispatchCandidate toCandidate(Object[] r) {
        return new DispatchCandidate(
            toUuid(r[0]),
            toUuid(r[1]),
            RunPriority.valueOf((String) r[2]),
            toInstant(r[3]),
            ((Number) r[4]).intValue(),
            (String) r[5]);
    }

    private static QueueRow toRow(RunQueueEntity e) {
        return new QueueRow(e.getRunId(), e.getOrgId(), e.getScheduleId(), e.getPriority(),
            e.getQueueState(), e.isCancelRequested(), e.getRequestedEventJson(),
            e.getRetryOf(), e.getRetryCount());
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp type: " + value.getClass());
    }
}
