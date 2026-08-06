package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.domain.RunStats;

import java.time.Instant;
import java.util.UUID;

public interface GetRunStatsUseCase {

    /**
     * Aggregates completed (PASSED/FAILED) run counts for a project since a
     * given instant. Used by the result module's analytics.
     */
    RunStats getStats(UUID projectId, UUID orgId, Instant since);
}
