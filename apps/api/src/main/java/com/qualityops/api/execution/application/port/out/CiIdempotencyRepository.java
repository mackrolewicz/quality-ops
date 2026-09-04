package com.qualityops.api.execution.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** ADR-007 §5. The {@code (org_id, idempotency_key)} unique constraint is the
 *  arbiter for a concurrent first-call race — {@link #insert} does NO pre-check;
 *  callers catch {@code DataIntegrityViolationException} and re-read. */
public interface CiIdempotencyRepository {

    Optional<CiIdempotencyRow> find(UUID orgId, String idempotencyKey);

    void insert(UUID orgId, String idempotencyKey, String requestFingerprint, UUID runId);

    int deleteOlderThan(Instant cutoff);

    record CiIdempotencyRow(UUID id, UUID orgId, String idempotencyKey, String requestFingerprint,
                            UUID runId, Instant createdAt) {}
}
