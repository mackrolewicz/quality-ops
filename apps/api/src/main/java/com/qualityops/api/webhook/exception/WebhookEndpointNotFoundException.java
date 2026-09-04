package com.qualityops.api.webhook.exception;

import com.qualityops.api.common.NotFoundException;

import java.util.UUID;

public class WebhookEndpointNotFoundException extends NotFoundException {

    public WebhookEndpointNotFoundException(UUID id) {
        super("WEBHOOK_ENDPOINT_NOT_FOUND", "Webhook endpoint not found: " + id);
    }
}
