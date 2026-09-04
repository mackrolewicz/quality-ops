package com.qualityops.api.webhook.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR-007 §6.2. Every row is org-scoped; {@code projectId} nullable (NULL => all
 *  runs in the org). */
public interface WebhookEndpointRepository {

    WebhookEndpointRow create(UUID orgId, UUID projectId, String url, String secret,
                              boolean enabled, UUID createdBy);

    List<WebhookEndpointRow> listForProject(UUID orgId, UUID projectId);

    Optional<WebhookEndpointRow> findById(UUID id, UUID orgId);

    boolean deleteByIdAndOrgId(UUID id, UUID orgId);

    /** Enabled endpoints matching this run: project-scoped OR org-wide (project_id NULL). */
    List<WebhookEndpointRow> findEnabledForOrgAndProject(UUID orgId, UUID projectId);

    record WebhookEndpointRow(UUID id, UUID orgId, UUID projectId, String url, String secret,
                              boolean enabled, Instant createdAt) {}
}
