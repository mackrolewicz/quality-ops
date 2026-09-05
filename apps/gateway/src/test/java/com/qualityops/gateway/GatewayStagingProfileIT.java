package com.qualityops.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-008 §8 — the opt-in {@code staging} TLS profile must not break the gateway
 * context. Runs with {@code GATEWAY_TLS_ENABLED=false} (the LB/ingress-termination
 * case) so no keystore is needed; only asserts the context starts and the port
 * placeholder resolves.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"GATEWAY_TLS_ENABLED=false"}
)
@ActiveProfiles("staging")
class GatewayStagingProfileIT {

    @Value("${local.server.port}")
    int port;

    @Test
    void stagingProfile_withTlsDisabled_contextStarts() {
        assertThat(port).isGreaterThan(0);
    }
}
