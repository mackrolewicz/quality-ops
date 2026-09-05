package com.qualityops.api.scheduling.exception;

import com.qualityops.api.common.NotFoundException;

public class ScheduleNotFoundException extends NotFoundException {
    public ScheduleNotFoundException(String message) {
        super("SCHEDULE_NOT_FOUND", message);
    }
}
