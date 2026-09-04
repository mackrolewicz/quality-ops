package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.dto.RunResponse;

import java.util.UUID;

public interface CancelRunUseCase {

    CancelResult cancel(UUID runId, UUID orgId);

    enum Outcome { CANCELLED_QUEUED, CANCEL_REQUESTED, NOT_CANCELLABLE }

    record CancelResult(Outcome outcome, RunResponse run /* null when NOT_CANCELLABLE */) {
        public static CancelResult notCancellable() {
            return new CancelResult(Outcome.NOT_CANCELLABLE, null);
        }
    }
}
