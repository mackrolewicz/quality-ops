package com.qualityops.api.execution.application.port.in;

import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunStartedEvent;

/**
 * Applies run-lifecycle facts emitted by the Worker to the API's authoritative
 * run state. Every implementation must be idempotent — at-least-once Kafka
 * delivery means each method may be invoked more than once per event.
 */
public interface ApplyRunLifecycleUseCase {
    void onRunStarted(RunStartedEvent event);
    void onRunCompleted(RunCompletedEvent event);
    void onRunFailed(RunFailedEvent event);
}
