package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.dto.CreateRunRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CiRunFingerprintTest {

    private final UUID project = UUID.randomUUID();
    private final UUID suite = UUID.randomUUID();
    private final UUID env = UUID.randomUUID();

    @Test
    void fingerprint_sameBody_sameHash() {
        var a = new CreateRunRequest(project, suite, env, "NORMAL");
        var b = new CreateRunRequest(project, suite, env, "NORMAL");

        assertThat(CiRunService.fingerprint(a)).isEqualTo(CiRunService.fingerprint(b));
    }

    @Test
    void fingerprint_nullPriority_equalsExplicitNormal() {
        var nullPriority = new CreateRunRequest(project, suite, env, null);
        var normal = new CreateRunRequest(project, suite, env, "NORMAL");

        assertThat(CiRunService.fingerprint(nullPriority)).isEqualTo(CiRunService.fingerprint(normal));
    }

    @Test
    void fingerprint_differentSuite_differentHash() {
        var a = new CreateRunRequest(project, suite, env, null);
        var b = new CreateRunRequest(project, UUID.randomUUID(), env, null);

        assertThat(CiRunService.fingerprint(a)).isNotEqualTo(CiRunService.fingerprint(b));
    }

    @Test
    void fingerprint_isLowercaseHex64() {
        var fp = CiRunService.fingerprint(new CreateRunRequest(project, suite, env, null));

        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
    }
}
