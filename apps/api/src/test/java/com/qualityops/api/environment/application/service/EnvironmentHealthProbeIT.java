package com.qualityops.api.environment.application.service;

import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe.ProbeResult;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository.RecordProbeCommand;
import com.qualityops.api.environment.domain.EnvironmentHealthStatus;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-008 §3 — the probe sweep against the real Flyway schema. The HTTP exchange
 * itself is covered by {@code HttpEnvironmentHealthProbeTest}; here the
 * {@link EnvironmentHealthProbe} out-port is mocked so classification + persistence
 * are deterministic and the SSRF guard is exercised on the real {@code base_url}
 * (MockWebServer binds to loopback, which the guard denies unconditionally).
 */
class EnvironmentHealthProbeIT extends AbstractPostgresIT {

    // example.com is IANA-reserved and resolves to a public address (a subdomain
    // would NXDOMAIN and be treated as a blocked target by OutboundAddressGuard).
    private static final String PUBLIC_URL = "https://example.com";

    @Autowired private EnvironmentHealthService service;
    @Autowired private EnvironmentHealthRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private EnvironmentHealthProbe probe;

    private UUID orgA;
    private UUID projectA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        projectA = ItFixtures.insertProject(jdbc, orgA);
        // Defensive default: selectDueBatch is a platform scan and may pick up
        // STAGING envs seeded by other IT classes; keep those probes non-null.
        when(probe.probe(any())).thenReturn(new ProbeResult(true, 200, 1, null));
    }

    private String healthStatus(UUID envId) {
        return jdbc.queryForObject("SELECT health_status FROM environments WHERE id = ?", String.class, envId);
    }

    private int consecutiveFailures(UUID envId) {
        return jdbc.queryForObject(
            "SELECT consecutive_failures FROM environments WHERE id = ?", Integer.class, envId);
    }

    private int checkRows(UUID envId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM environment_health_check WHERE environment_id = ?", Integer.class, envId);
    }

    private void backdateProbe(UUID envId) {
        jdbc.update("UPDATE environments SET last_probe_at = now() - interval '30 minutes' WHERE id = ?", envId);
    }

    @Test
    void sweep_staging200_marksHealthyAndSetsLastHealthyAt() {
        when(probe.probe(eq(PUBLIC_URL))).thenReturn(new ProbeResult(true, 200, 12, null));
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", PUBLIC_URL);

        service.sweep();

        assertThat(healthStatus(env)).isEqualTo("HEALTHY");
        assertThat(jdbc.queryForObject(
            "SELECT last_healthy_at FROM environments WHERE id = ?", Timestamp.class, env)).isNotNull();
        assertThat(checkRows(env)).isEqualTo(1);
    }

    @Test
    void sweep_staging503ThreeTimes_marksDown() {
        when(probe.probe(eq(PUBLIC_URL))).thenReturn(new ProbeResult(true, 503, 5, null));
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", PUBLIC_URL);

        service.sweep();
        backdateProbe(env);
        service.sweep();
        backdateProbe(env);
        service.sweep();

        assertThat(healthStatus(env)).isEqualTo("DOWN");
        assertThat(consecutiveFailures(env)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void sweep_devEnvironment_isNeverSelected() {
        UUID dev = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "DEV", PUBLIC_URL);

        service.sweep();

        assertThat(healthStatus(dev)).isEqualTo("UNKNOWN");
        assertThat(checkRows(dev)).isZero();
    }

    @Test
    void sweep_writesAreOrgScoped_recordProbeGuardsByOrgId() {
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", PUBLIC_URL);
        UUID foreignOrg = ItFixtures.insertOrg(jdbc);

        repo.recordProbe(new RecordProbeCommand(env, foreignOrg, projectA, EnvironmentHealthStatus.DOWN,
            Instant.now(), 500, 9, "forced", 5, null));

        assertThat(healthStatus(env)).isEqualTo("UNKNOWN");
        assertThat(checkRows(env)).isZero();
    }

    @Test
    void sweep_privateTargetStaging_recordsUnknownBlocked_noSocket() {
        String privateUrl = "http://127.0.0.1:9";
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", privateUrl);

        service.sweep();

        assertThat(healthStatus(env)).isEqualTo("UNKNOWN");
        assertThat(consecutiveFailures(env)).isZero();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT error_detail FROM environment_health_check WHERE environment_id = ?", env);
        assertThat((String) row.get("error_detail")).contains("blocked");
        verify(probe, never()).probe(eq(privateUrl));
    }

    @Test
    void sweep_twiceWithinProbeInterval_isIdempotent() {
        when(probe.probe(eq(PUBLIC_URL))).thenReturn(new ProbeResult(true, 200, 3, null));
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", PUBLIC_URL);

        service.sweep();
        service.sweep();

        assertThat(checkRows(env)).isEqualTo(1);
    }
}
