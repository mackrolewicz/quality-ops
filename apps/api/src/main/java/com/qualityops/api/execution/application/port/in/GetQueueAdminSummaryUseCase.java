package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.dto.QueueAdminSummary;

import java.util.UUID;

/** ADR-007 §3 — {@code GET /api/v1/admin/queue}. */
public interface GetQueueAdminSummaryUseCase {

    QueueAdminSummary summary(UUID orgId);
}
