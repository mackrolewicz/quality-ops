package com.qualityops.api.webhook.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface WebhookDeliveryJpaRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    @Modifying
    @Query(value = """
        INSERT INTO webhook_delivery (id, org_id, webhook_endpoint_id, run_id, event_type, payload_json,
                                      state, attempt, next_attempt_at)
        VALUES (gen_random_uuid(), :orgId, :endpointId, :runId, :type, CAST(:payload AS jsonb),
                'PENDING', 0, now())
        ON CONFLICT (run_id, webhook_endpoint_id) DO NOTHING
        """, nativeQuery = true)
    int insertIgnoreConflict(@Param("orgId") UUID orgId, @Param("endpointId") UUID endpointId,
                             @Param("runId") UUID runId, @Param("type") String type,
                             @Param("payload") String payload);

    @Query(value = """
        SELECT id, org_id, webhook_endpoint_id, run_id, event_type, attempt, payload_json
        FROM webhook_delivery
        WHERE state = 'PENDING' AND next_attempt_at <= now()
        ORDER BY next_attempt_at
        LIMIT :batch
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Object[]> selectDue(@Param("batch") int batch);

    // The outcome writes are conditional on the row still being PENDING (and, for
    // a retry, on the attempt count the sender started from). If a ShedLock lease
    // expired mid-batch and a second replica re-sent the same row, its stale
    // outcome write matches 0 rows instead of resurrecting a DELIVERED row.
    @Modifying
    @Query(value = "UPDATE webhook_delivery SET state = 'DELIVERED', updated_at = now() "
        + "WHERE id = :id AND state = 'PENDING'", nativeQuery = true)
    void markDelivered(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        UPDATE webhook_delivery
        SET attempt = :attempt, last_error = :err, next_attempt_at = :next, updated_at = now()
        WHERE id = :id AND state = 'PENDING' AND attempt = :attempt - 1
        """, nativeQuery = true)
    void markRetry(@Param("id") UUID id, @Param("attempt") int attempt,
                   @Param("err") String err, @Param("next") Instant next);

    @Modifying
    @Query(value = """
        UPDATE webhook_delivery
        SET state = 'EXHAUSTED', attempt = :attempt, last_error = :err, updated_at = now()
        WHERE id = :id AND state = 'PENDING'
        """, nativeQuery = true)
    void markExhausted(@Param("id") UUID id, @Param("attempt") int attempt, @Param("err") String err);

    @Modifying
    @Query(value = """
        DELETE FROM webhook_delivery
        WHERE state IN ('DELIVERED', 'EXHAUSTED') AND updated_at < :cutoff
        """, nativeQuery = true)
    int deleteTerminalOlderThan(@Param("cutoff") Instant cutoff);
}
