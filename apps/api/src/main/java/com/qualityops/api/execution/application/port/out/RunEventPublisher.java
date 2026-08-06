package com.qualityops.api.execution.application.port.out;

import com.qualityops.api.execution.event.RunCompletedEvent;
import com.qualityops.api.execution.event.RunRequestedEvent;

public interface RunEventPublisher {
    void publishRunRequested(RunRequestedEvent event);

    void publishRunCompleted(RunCompletedEvent event);
}
