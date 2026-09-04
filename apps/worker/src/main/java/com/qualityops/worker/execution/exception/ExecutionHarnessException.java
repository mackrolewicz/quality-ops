package com.qualityops.worker.execution.exception;

/** A genuine worker/harness fault (not a test failure, timeout, blocked target
 *  or connection error — those are encoded in the CaseExecutionResult). */
public class ExecutionHarnessException extends RuntimeException {
    public ExecutionHarnessException(String message) {
        super(message);
    }

    public ExecutionHarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
