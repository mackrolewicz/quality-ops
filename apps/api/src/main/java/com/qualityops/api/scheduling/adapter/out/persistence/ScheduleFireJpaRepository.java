package com.qualityops.api.scheduling.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface ScheduleFireJpaRepository extends JpaRepository<ScheduleFireEntity, UUID> {

    @Modifying
    @Query(value = """
        INSERT INTO schedule_fire (org_id, schedule_id, fire_slot)
        VALUES (:orgId, :scheduleId, :fireSlot)
        ON CONFLICT (schedule_id, fire_slot) DO NOTHING
        """, nativeQuery = true)
    int tryInsert(@Param("orgId") UUID orgId, @Param("scheduleId") UUID scheduleId,
                  @Param("fireSlot") Instant fireSlot);

    @Modifying
    @Query(value = "UPDATE schedule_fire SET run_id = :runId WHERE schedule_id = :scheduleId AND fire_slot = :slot",
        nativeQuery = true)
    int attachRun(@Param("scheduleId") UUID scheduleId, @Param("slot") Instant slot, @Param("runId") UUID runId);

    @Modifying
    @Query(value = "DELETE FROM schedule_fire WHERE created_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
