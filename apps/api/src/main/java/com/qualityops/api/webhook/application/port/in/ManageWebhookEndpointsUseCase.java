package com.qualityops.api.webhook.application.port.in;

import com.qualityops.api.webhook.dto.RegisterWebhookRequest;
import com.qualityops.api.webhook.dto.WebhookEndpointResponse;

import java.util.List;
import java.util.UUID;

/** ADR-007 §6.2 — OWNER/ADMIN CRUD over their own project's webhook endpoints. */
public interface ManageWebhookEndpointsUseCase {

    WebhookEndpointResponse register(UUID projectId, UUID orgId, RegisterWebhookRequest req, UUID userId);

    List<WebhookEndpointResponse> list(UUID projectId, UUID orgId);

    void delete(UUID id, UUID orgId);
}
