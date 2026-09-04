package com.qualityops.api.webhook.application.service;

import org.springframework.stereotype.Component;

import com.qualityops.api.common.net.OutboundAddressGuard;

/** ADR-007 §6.2 / ADR-008 §3 — https-only + private-IP denylist. Thin delegate to
 *  the shared {@link OutboundAddressGuard} (no http, no private targets).
 *  Throws {@link IllegalArgumentException} (-> 400 {@code VALIDATION_ERROR}). */
@Component
public class WebhookUrlValidator {

    private final OutboundAddressGuard guard;

    public WebhookUrlValidator(OutboundAddressGuard guard) {
        this.guard = guard;
    }

    public void validate(String url) {
        guard.check(url, false, false);
    }
}
