package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.application.port.out.CiIdempotencyRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class CiIdempotencyRepositoryAdapter implements CiIdempotencyRepository {

    private final CiIdempotencyJpaRepository jpa;

    CiIdempotencyRepositoryAdapter(CiIdempotencyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CiIdempotencyRow> find(UUID orgId, String idempotencyKey) {
        return jpa.findByOrgIdAndIdempotencyKey(orgId, idempotencyKey).map(CiIdempotencyRepositoryAdapter::toRow);
    }

    @Override
    public void insert(UUID orgId, String idempotencyKey, String requestFingerprint, UUID runId) {
        // No pre-check: the UNIQUE (org_id, idempotency_key) constraint is the
        // race arbiter — the caller catches DataIntegrityViolationException.
        jpa.save(CiIdempotencyKeyEntity.create(orgId, idempotencyKey, requestFingerprint, runId));
    }

    @Override
    @Transactional
    public int deleteOlderThan(Instant cutoff) {
        return jpa.deleteOlderThan(cutoff);
    }

    private static CiIdempotencyRow toRow(CiIdempotencyKeyEntity e) {
        return new CiIdempotencyRow(e.getId(), e.getOrgId(), e.getIdempotencyKey(),
            e.getRequestFingerprint(), e.getRunId(), e.getCreatedAt());
    }
}
