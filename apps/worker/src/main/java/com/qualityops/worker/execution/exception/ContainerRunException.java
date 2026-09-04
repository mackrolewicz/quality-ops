package com.qualityops.worker.execution.exception;

/** ADR-009 §1 — a failure to create / start / wait / inspect a runner container
 *  (transport error, daemon error, unexpected 5xx). Distinct from a non-zero
 *  container exit, which is a normal {@code ContainerRunResult}. */
public class ContainerRunException extends RuntimeException {

    public ContainerRunException(String message) {
        super(message);
    }

    public ContainerRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
