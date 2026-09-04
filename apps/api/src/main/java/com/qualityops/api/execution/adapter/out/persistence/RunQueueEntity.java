package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Mapped for ddl-auto=validate and used by the JPQL cross-entity subquery in
 *  RunJpaRepository. priority/queue_state are VARCHAR + CHECK in the DB -> plain
 *  STRING enums (NOT NAMED_ENUM). requested_event_json is nullable (nulled at
 *  terminal), so the column omits nullable=false. */
@Entity
@Table(name = "run_queue")
class RunQueueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_state", nullable = false, length = 16)
    private QueueState queueState;

    @Column(name = "requested_event_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String requestedEventJson;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "dispatch_attempts", nullable = false)
    private int dispatchAttempts;

    @Column(name = "last_dispatch_at")
    private Instant lastDispatchAt;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "retry_of")
    private UUID retryOf;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RunQueueEntity() {}

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getScheduleId() {
        return scheduleId;
    }

    RunPriority getPriority() {
        return priority;
    }

    QueueState getQueueState() {
        return queueState;
    }

    String getRequestedEventJson() {
        return requestedEventJson;
    }

    Instant getEnqueuedAt() {
        return enqueuedAt;
    }

    Instant getDispatchedAt() {
        return dispatchedAt;
    }

    int getDispatchAttempts() {
        return dispatchAttempts;
    }

    Instant getLastDispatchAt() {
        return lastDispatchAt;
    }

    boolean isCancelRequested() {
        return cancelRequested;
    }

    Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    Instant getTerminalAt() {
        return terminalAt;
    }

    UUID getRetryOf() {
        return retryOf;
    }

    int getRetryCount() {
        return retryCount;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    static RunQueueEntity create(UUID id, UUID orgId, UUID runId, UUID scheduleId,
                                 RunPriority priority, QueueState queueState, String requestedEventJson,
                                 Instant enqueuedAt, int dispatchAttempts, boolean cancelRequested,
                                 Instant createdAt) {
        var entity = new RunQueueEntity();
        entity.id = id;
        entity.orgId = orgId;
        entity.runId = runId;
        entity.scheduleId = scheduleId;
        entity.priority = priority;
        entity.queueState = queueState;
        entity.requestedEventJson = requestedEventJson;
        entity.enqueuedAt = enqueuedAt;
        entity.dispatchAttempts = dispatchAttempts;
        entity.cancelRequested = cancelRequested;
        entity.createdAt = createdAt;
        return entity;
    }

    /** ADR-007 §2.3 — a fresh QUEUED row linked back to the original via
     *  {@code retry_of}, carrying the monotone {@code retry_count}. */
    static RunQueueEntity createRetry(UUID id, UUID orgId, UUID runId, UUID scheduleId,
                                      RunPriority priority, QueueState queueState, String eventJson,
                                      Instant enqueuedAt, UUID retryOf, int retryCount) {
        var entity = create(id, orgId, runId, scheduleId, priority, queueState, eventJson,
            enqueuedAt, 0, false, enqueuedAt);
        entity.retryOf = retryOf;
        entity.retryCount = retryCount;
        return entity;
    }
}
