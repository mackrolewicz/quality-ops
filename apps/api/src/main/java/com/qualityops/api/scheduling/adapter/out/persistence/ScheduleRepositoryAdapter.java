package com.qualityops.api.scheduling.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.domain.Schedule;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ScheduleRepositoryAdapter implements ScheduleRepository {

    private final ScheduleJpaRepository jpa;

    ScheduleRepositoryAdapter(ScheduleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public Schedule save(Schedule schedule) {
        return jpa.save(ScheduleEntity.fromDomain(schedule)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Schedule> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgId(id, orgId).map(ScheduleEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Schedule> findByOrgAndProject(UUID orgId, UUID projectId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findByOrgIdAndProjectId(orgId, projectId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(ScheduleEntity::toDomain).toList(),
            safePage, safeSize, result.getTotalElements());
    }

    @Override
    @Transactional
    public void deleteByIdAndOrgId(UUID id, UUID orgId) {
        jpa.findByIdAndOrgId(id, orgId).ifPresent(jpa::delete);
    }

    @Override
    @Transactional
    public List<Schedule> findDue(int batch) {
        return jpa.findDue(batch).stream().map(ScheduleEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void advanceNextFireAt(UUID id, UUID orgId, Instant next, Instant firedAt) {
        jpa.advanceNextFireAt(id, orgId, next, firedAt);
    }

    @Override
    @Transactional
    public void markOneTimeFired(UUID id, UUID orgId, Instant firedAt) {
        jpa.markOneTimeFired(id, orgId, firedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(UUID id, UUID orgId, String err, Instant at) {
        jpa.abandon(id, orgId, err, at);
    }
}
