package com.qualityops.api.scheduling.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.scheduling.domain.Schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleRepository {

    Schedule save(Schedule schedule);

    Optional<Schedule> findByIdAndOrgId(UUID id, UUID orgId);

    PageResult<Schedule> findByOrgAndProject(UUID orgId, UUID projectId, int page, int size);

    void deleteByIdAndOrgId(UUID id, UUID orgId);

    /** Due schedules, row-locked with SKIP LOCKED for the tick. */
    List<Schedule> findDue(int batch);

    void advanceNextFireAt(UUID id, UUID orgId, Instant next, Instant firedAt);

    void markOneTimeFired(UUID id, UUID orgId, Instant firedAt);

    /** Separate transaction — an abandoned schedule persists even if the caller rolls back. */
    void abandon(UUID id, UUID orgId, String err, Instant at);
}
