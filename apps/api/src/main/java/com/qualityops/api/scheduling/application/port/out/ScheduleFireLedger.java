package com.qualityops.api.scheduling.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface ScheduleFireLedger {

    /** INSERT ... ON CONFLICT (schedule_id, fire_slot) DO NOTHING.
     *  Returns true iff this call created the row (i.e. won the occurrence). */
    boolean tryInsert(UUID orgId, UUID scheduleId, Instant fireSlot);

    void attachRun(UUID scheduleId, Instant slot, UUID runId);

    int deleteOlderThan(Instant cutoff);
}
