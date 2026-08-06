package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.event.RunRequestedEvent;

public interface ProcessRunRequestedUseCase {

    /**
     * Handles a run-requested event. Must be idempotent — safe to invoke
     * more than once for the same event (at-least-once Kafka delivery).
     */
    void processRunRequested(RunRequestedEvent event);
}
