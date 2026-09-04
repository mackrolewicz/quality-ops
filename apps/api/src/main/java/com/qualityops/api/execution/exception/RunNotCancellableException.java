package com.qualityops.api.execution.exception;

import com.qualityops.api.common.ConflictException;

import java.util.UUID;

public class RunNotCancellableException extends ConflictException {

    public RunNotCancellableException(UUID runId) {
        super("RUN_NOT_CANCELLABLE", "Run is not in a cancellable state: " + runId);
    }
}
