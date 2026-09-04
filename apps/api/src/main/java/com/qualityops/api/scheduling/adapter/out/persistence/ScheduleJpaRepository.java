package com.qualityops.api.scheduling.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ScheduleJpaRepository extends JpaRepository<ScheduleEntity, UUID> {

    Optional<ScheduleEntity> findByIdAndOrgId(UUID id, UUID orgId);

    Page<ScheduleEntity> findByOrgIdAndProjectId(UUID orgId, UUID projectId, Pageable pageable);

    @Query(value = """
        SELECT * FROM schedule
        WHERE enabled AND next_fire_at IS NOT NULL AND next_fire_at <= now()
        ORDER BY next_fire_at
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ScheduleEntity> findDue(@Param("batch") int batch);

    @Modifying
    @Query("UPDATE ScheduleEntity s SET s.nextFireAt = :next, s.lastFiredAt = :firedAt, s.updatedAt = :firedAt "
        + "WHERE s.id = :id AND s.orgId = :orgId")
    int advanceNextFireAt(@Param("id") UUID id, @Param("orgId") UUID orgId,
                          @Param("next") Instant next, @Param("firedAt") Instant firedAt);

    @Modifying
    @Query("UPDATE ScheduleEntity s SET s.nextFireAt = NULL, s.enabled = false, "
        + "s.lastFiredAt = :firedAt, s.updatedAt = :firedAt WHERE s.id = :id AND s.orgId = :orgId")
    int markOneTimeFired(@Param("id") UUID id, @Param("orgId") UUID orgId, @Param("firedAt") Instant firedAt);

    @Modifying
    @Query("UPDATE ScheduleEntity s SET s.enabled = false, s.nextFireAt = NULL, "
        + "s.lastError = :err, s.lastErrorAt = :at, s.updatedAt = :at WHERE s.id = :id AND s.orgId = :orgId")
    int abandon(@Param("id") UUID id, @Param("orgId") UUID orgId,
                @Param("err") String err, @Param("at") Instant at);
}
