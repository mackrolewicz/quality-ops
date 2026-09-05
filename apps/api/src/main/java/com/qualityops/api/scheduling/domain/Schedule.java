package com.qualityops.api.scheduling.domain;

import com.qualityops.api.execution.domain.RunPriority;

import java.time.Instant;
import java.util.UUID;

/** Aggregate root for a run schedule. Immutable value object; the service builds
 *  a fresh instance on every mutation. Invariants (RECURRING => cron+tz set,
 *  fireAt null; ONE_TIME => fireAt set, cron/tz null) are enforced at the DTO
 *  boundary by ScheduleConsistentValidator and re-checked in ScheduleService. */
public record Schedule(
        UUID id,
        UUID orgId,
        UUID projectId,
        UUID suiteId,
        UUID environmentId,
        String name,
        ScheduleKind kind,
        String cronExpression,   // nullable
        String timeZone,         // nullable (IANA zone id)
        Instant fireAt,          // nullable
        RunPriority priority,
        CatchUpPolicy catchUpPolicy,
        boolean enabled,
        Instant nextFireAt,      // nullable — materialised
        Instant lastFiredAt,     // nullable
        String lastError,        // nullable
        Instant lastErrorAt,     // nullable
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {}
