package com.qualityops.api.execution.dto;

import java.util.Map;

/** ADR-007 §3. {@code org} block is scoped to the caller's org; {@code process}
 *  block is process-wide (labelled as such). */
public record QueueAdminSummary(OrgQueue org, ProcessQueue process) {

    public record OrgQueue(
            Map<String, Long> queuedByPriority,
            Long oldestQueuedAgeSeconds,
            long activeRuns,
            int effectiveMaxActiveRuns,
            String maxActiveRunsSource) {}

    public record ProcessQueue(
            double dispatchThroughput,
            Map<String, Double> dispatchFailed,
            Map<String, Double> reaped,
            Map<String, Double> retries) {}
}
