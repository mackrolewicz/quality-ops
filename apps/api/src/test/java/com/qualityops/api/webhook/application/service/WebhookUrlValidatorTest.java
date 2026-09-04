package com.qualityops.api.webhook.application.service;

import org.junit.jupiter.api.Test;

import com.qualityops.api.common.net.OutboundAddressGuard;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator(new OutboundAddressGuard());

    @Test
    void validate_httpScheme_rejected() {
        assertThatThrownBy(() -> validator.validate("http://ci.example.com/hook"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_loopback_rejected() {
        assertThatThrownBy(() -> validator.validate("https://127.0.0.1/hook"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_privateRfc1918_rejected() {
        assertThatThrownBy(() -> validator.validate("https://10.0.0.5/hook"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_linkLocalMetadata_rejected() {
        assertThatThrownBy(() -> validator.validate("https://169.254.169.254/latest"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_ipv6Loopback_rejected() {
        assertThatThrownBy(() -> validator.validate("https://[::1]/hook"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_publicHttpsHost_accepted() {
        // example.com is an IANA-reserved domain that resolves to a public address.
        assertThatCode(() -> validator.validate("https://example.com/hook"))
            .doesNotThrowAnyException();
    }
}
