package com.qualityops.api.scheduling.dto;

import com.qualityops.api.scheduling.dto.validation.ScheduleConsistent;
import com.qualityops.api.scheduling.dto.validation.ScheduleRequestView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Target (suite/environment) is immutable post-create — this only changes the
 *  label, timing, priority and catch-up policy. */
@ScheduleConsistent
public record UpdateScheduleRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Pattern(regexp = "ONE_TIME|RECURRING") String kind,
        String cronExpression,
        String timeZone,
        Instant fireAt,
        @Pattern(regexp = "HIGH|NORMAL|LOW") String priority,
        @Pattern(regexp = "SKIP_MISSED|FIRE_ONCE") String catchUpPolicy
) implements ScheduleRequestView {
}
