package com.qualityops.worker.execution.application.port.in;

import com.qualityops.events.RunRequestedEvent;

public interface ProcessRunRequestedUseCase {

    /** Idempotent: safe to invoke more than once for the same event. */
    void processRunRequested(RunRequestedEvent event);
}
