package com.qualityops.api.webhook.application.service;

import org.junit.jupiter.api.Test;

import com.qualityops.api.common.net.OutboundAddressGuard;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the ADR-008 §3 extraction: {@link WebhookUrlValidator} is now a thin
 *  delegate to {@link OutboundAddressGuard}, but its external contract (https-only,
 *  private targets denied) must be unchanged. */
class WebhookUrlValidatorDelegationTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator(new OutboundAddressGuard());

    @Test
    void validate_privateHost_throws() {
        assertThatThrownBy(() -> validator.validate("https://10.0.0.5/hook"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_httpsPublic_returnsNormally() {
        assertThatCode(() -> validator.validate("https://example.com/hook"))
            .doesNotThrowAnyException();
    }
}
