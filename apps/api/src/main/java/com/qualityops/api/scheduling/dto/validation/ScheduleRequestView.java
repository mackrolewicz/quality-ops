package com.qualityops.api.scheduling.dto.validation;

import java.time.Instant;

/** One shape both CreateScheduleRequest and UpdateScheduleRequest expose so a
 *  single {@link ScheduleConsistentValidator} covers both. */
public interface ScheduleRequestView {

    String kind();

    String cronExpression();

    String timeZone();

    Instant fireAt();
}
