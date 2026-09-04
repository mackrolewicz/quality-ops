package com.qualityops.api.scheduling.exception;

import com.qualityops.api.common.NotFoundException;

public class ScheduleTargetNotFoundException extends NotFoundException {
    public ScheduleTargetNotFoundException(String message) {
        super("SCHEDULE_TARGET_NOT_FOUND", message);
    }
}
