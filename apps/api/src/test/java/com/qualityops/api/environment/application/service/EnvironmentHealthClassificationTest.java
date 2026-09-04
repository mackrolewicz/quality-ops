package com.qualityops.api.environment.application.service;

import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe.ProbeResult;
import com.qualityops.api.environment.application.service.EnvironmentHealthService.Classification;
import com.qualityops.api.environment.domain.EnvironmentHealthStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §3 — the pure {@code classify(...)} function (degraded-after=1, failure-threshold=3). */
class EnvironmentHealthClassificationTest {

    @Test
    void classify_reachable200_returnsHealthyAndResetsFailures() {
        Classification c = EnvironmentHealthService.classify(2, new ProbeResult(true, 200, 10, null), 1, 3);

        assertThat(c.status()).isEqualTo(EnvironmentHealthStatus.HEALTHY);
        assertThat(c.consecutiveFailures()).isZero();
        assertThat(c.healthy()).isTrue();
    }

    @Test
    void classify_reachable301_returnsHealthy() {
        Classification c = EnvironmentHealthService.classify(0, new ProbeResult(true, 301, 5, null), 1, 3);

        assertThat(c.status()).isEqualTo(EnvironmentHealthStatus.HEALTHY);
    }

    @Test
    void classify_firstFailure_returnsDegraded() {
        Classification c = EnvironmentHealthService.classify(0, new ProbeResult(true, 503, 8, null), 1, 3);

        assertThat(c.status()).isEqualTo(EnvironmentHealthStatus.DEGRADED);
        assertThat(c.consecutiveFailures()).isEqualTo(1);
        assertThat(c.healthy()).isFalse();
    }

    @Test
    void classify_thirdFailure_returnsDown() {
        Classification c = EnvironmentHealthService.classify(2, new ProbeResult(true, 503, 8, null), 1, 3);

        assertThat(c.status()).isEqualTo(EnvironmentHealthStatus.DOWN);
        assertThat(c.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void classify_unreachable_countsAsFailure() {
        Classification c = EnvironmentHealthService.classify(
            0, new ProbeResult(false, null, 5000, "HttpTimeoutException"), 1, 3);

        assertThat(c.status()).isEqualTo(EnvironmentHealthStatus.DEGRADED);
        assertThat(c.consecutiveFailures()).isEqualTo(1);
    }
}
