package com.qualityops.api.scheduling.adapter.out.persistence;

import com.qualityops.api.scheduling.application.port.out.ScheduleFireLedger;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
@Transactional
class ScheduleFireLedgerAdapter implements ScheduleFireLedger {

    private final ScheduleFireJpaRepository jpa;

    ScheduleFireLedgerAdapter(ScheduleFireJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean tryInsert(UUID orgId, UUID scheduleId, Instant fireSlot) {
        return jpa.tryInsert(orgId, scheduleId, fireSlot) > 0;
    }

    @Override
    public void attachRun(UUID scheduleId, Instant slot, UUID runId) {
        jpa.attachRun(scheduleId, slot, runId);
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jpa.deleteOlderThan(cutoff);
    }
}
