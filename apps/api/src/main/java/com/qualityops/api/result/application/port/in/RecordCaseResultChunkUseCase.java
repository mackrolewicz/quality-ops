package com.qualityops.api.result.application.port.in;

import com.qualityops.events.ResultChunkEvent;

public interface RecordCaseResultChunkUseCase {

    /**
     * Applies one per-case {@code results.chunk} to authoritative state: an
     * org- and executionId-guarded, epoch-monotone upsert of the case's
     * {@code test_results} row and its {@code test_result_artifacts}.
     * Idempotent — duplicate or out-of-order chunks converge; a lost chunk is
     * corrected by {@code runs.completed}.
     */
    void recordChunk(ResultChunkEvent event);
}
