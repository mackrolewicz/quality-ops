package com.qualityops.api.scheduling.dto;

import com.qualityops.api.scheduling.domain.Schedule;

import java.time.Instant;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        UUID orgId,
        UUID projectId,
        UUID suiteId,
        UUID environmentId,
        String name,
        String kind,
        String cronExpression,
        String timeZone,
        Instant fireAt,
        String priority,
        String catchUpPolicy,
        boolean enabled,
        Instant nextFireAt,
        Instant lastFiredAt,
        String lastError,
        Instant lastErrorAt,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScheduleResponse from(Schedule s) {
        return new ScheduleResponse(s.id(), s.orgId(), s.projectId(), s.suiteId(), s.environmentId(),
            s.name(), s.kind().name(), s.cronExpression(), s.timeZone(), s.fireAt(),
            s.priority().name(), s.catchUpPolicy().name(), s.enabled(), s.nextFireAt(), s.lastFiredAt(),
            s.lastError(), s.lastErrorAt(), s.createdBy(), s.createdAt(), s.updatedAt());
    }
}
