package com.qualityops.api.webhook.adapter.out.persistence;

import com.qualityops.api.webhook.application.port.out.WebhookDeliveryRepository;
import com.qualityops.api.webhook.domain.WebhookEventType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class WebhookDeliveryRepositoryAdapter implements WebhookDeliveryRepository {

    private final WebhookDeliveryJpaRepository jpa;

    WebhookDeliveryRepositoryAdapter(WebhookDeliveryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public int insertIgnoreConflict(UUID orgId, UUID endpointId, UUID runId, WebhookEventType type,
                                    String payloadJson) {
        return jpa.insertIgnoreConflict(orgId, endpointId, runId, type.name(), payloadJson);
    }

    @Override
    @Transactional // FOR UPDATE SKIP LOCKED — read-write transaction
    public List<DueDelivery> selectDue(int batch) {
        return jpa.selectDue(batch).stream()
            .map(r -> new DueDelivery(
                toUuid(r[0]),
                toUuid(r[1]),
                toUuid(r[2]),
                toUuid(r[3]),
                WebhookEventType.valueOf((String) r[4]),
                ((Number) r[5]).intValue(),
                (String) r[6]))
            .toList();
    }

    @Override
    @Transactional
    public void markDelivered(UUID id) {
        jpa.markDelivered(id);
    }

    @Override
    @Transactional
    public void markRetry(UUID id, int attempt, String lastError, Instant nextAttemptAt) {
        jpa.markRetry(id, attempt, lastError, nextAttemptAt);
    }

    @Override
    @Transactional
    public void markExhausted(UUID id, int attempt, String lastError) {
        jpa.markExhausted(id, attempt, lastError);
    }

    @Override
    @Transactional
    public int deleteTerminalOlderThan(Instant cutoff) {
        return jpa.deleteTerminalOlderThan(cutoff);
    }

    private static UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }
}
