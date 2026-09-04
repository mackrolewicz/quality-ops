package com.qualityops.api.execution.application.port.out;

import com.qualityops.events.RunCancelRequestedEvent;
import com.qualityops.events.RunRequestedEvent;

public interface RunEventPublisher {

    /** Publishes runs.requested and BLOCKS until the broker acks (bounded by
     *  qualityops.scheduling.queue.send-timeout). Throws RunEventPublishException
     *  on timeout / send failure so QueueDispatchService can roll the row back. */
    void publishRunRequested(RunRequestedEvent event);

    /** Best-effort fire-and-forget cancel command on runs.cancel (key = runId). */
    void publishRunCancelRequested(RunCancelRequestedEvent event);
}
