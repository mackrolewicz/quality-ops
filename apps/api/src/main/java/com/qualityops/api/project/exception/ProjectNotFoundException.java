package com.qualityops.api.project.exception;

import com.qualityops.api.common.NotFoundException;

public class ProjectNotFoundException extends NotFoundException {
    public ProjectNotFoundException(String message) {
        super("PROJECT_NOT_FOUND", message);
    }
}
