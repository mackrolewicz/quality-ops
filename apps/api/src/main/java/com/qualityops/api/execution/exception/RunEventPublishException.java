package com.qualityops.api.execution.exception;

/** Thrown by RunEventPublisher.publishRunRequested when the broker ack is not
 *  received within the send-timeout, so QueueDispatchService can roll the
 *  run_queue row back to QUEUED for a retry next tick. */
public class RunEventPublishException extends RuntimeException {

    public RunEventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
