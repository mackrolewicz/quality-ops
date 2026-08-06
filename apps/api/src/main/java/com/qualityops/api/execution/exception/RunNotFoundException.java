package com.qualityops.api.execution.exception;

import com.qualityops.api.common.NotFoundException;

public class RunNotFoundException extends NotFoundException {
    public RunNotFoundException(String message) {
        super("RUN_NOT_FOUND", message);
    }
}
