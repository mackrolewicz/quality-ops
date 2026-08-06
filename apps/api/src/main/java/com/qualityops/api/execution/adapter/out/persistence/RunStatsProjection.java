package com.qualityops.api.execution.adapter.out.persistence;

interface RunStatsProjection {
    Long getTotalRuns();

    Long getPassedRuns();

    Long getFailedRuns();
}
