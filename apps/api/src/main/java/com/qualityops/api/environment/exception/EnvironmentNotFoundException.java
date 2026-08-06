package com.qualityops.api.environment.exception;

import com.qualityops.api.common.NotFoundException;

public class EnvironmentNotFoundException extends NotFoundException {
    public EnvironmentNotFoundException(String message) {
        super("ENVIRONMENT_NOT_FOUND", message);
    }
}
