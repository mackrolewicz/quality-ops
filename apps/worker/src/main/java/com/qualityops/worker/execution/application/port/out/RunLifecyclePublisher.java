package com.qualityops.worker.execution.application.port.out;

import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunStartedEvent;

import java.util.UUID;

public interface RunLifecyclePublisher {
    void publishRunStarted(RunStartedEvent event);

    void publishRunCompleted(RunCompletedEvent event);

    void publishRunFailed(RunFailedEvent event);

    /** Per-case streaming update on {@code results.chunk} (key = runId).
     *  Fire-and-forget — a failure here is logged, never fatal, never blocks the terminal. */
    void publishResultChunk(ResultChunkEvent event);

    /** Re-publish a previously-serialised terminal event verbatim by topic
     *  (self-healing on AlreadyCompleted). */
    void republishTerminal(String topic, UUID runId, String terminalEventJson);
}
