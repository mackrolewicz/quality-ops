package com.qualityops.api.webhook.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final String SECRET = "0123456789abcdef";
    private static final long TS = 1_725_000_000L;
    private static final String BODY = "{\"event\":\"run.completed\"}";

    @Test
    void sign_isDeterministicForSameInputs() {
        assertThat(WebhookSignature.sign(SECRET, TS, BODY))
            .isEqualTo(WebhookSignature.sign(SECRET, TS, BODY))
            .startsWith("sha256=")
            .matches("sha256=[0-9a-f]{64}");
    }

    @Test
    void sign_tamperedBody_producesDifferentHex() {
        assertThat(WebhookSignature.sign(SECRET, TS, BODY))
            .isNotEqualTo(WebhookSignature.sign(SECRET, TS, BODY + " "));
    }

    @Test
    void sign_wrongSecret_producesDifferentHex() {
        assertThat(WebhookSignature.sign(SECRET, TS, BODY))
            .isNotEqualTo(WebhookSignature.sign("wrong-secret-value", TS, BODY));
    }

    @Test
    void constantTimeEquals_matchesAndMismatches() {
        String sig = WebhookSignature.sign(SECRET, TS, BODY);

        assertThat(WebhookSignature.constantTimeEquals(sig, sig)).isTrue();
        assertThat(WebhookSignature.constantTimeEquals(sig, "sha256=deadbeef")).isFalse();
    }
}
