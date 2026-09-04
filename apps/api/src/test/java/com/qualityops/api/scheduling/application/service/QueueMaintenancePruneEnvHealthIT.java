package com.qualityops.api.scheduling.application.service;

import com.qualityops.api.config.CiProperties;
import com.qualityops.api.config.EnvironmentHealthProperties;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.config.WebhookProperties;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository;
import com.qualityops.api.execution.application.port.out.CiIdempotencyRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.scheduling.application.port.out.ScheduleFireLedger;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.api.webhook.application.port.out.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-008 §3 — the fifth {@code prune()} delete removes {@code environment_health_check}
 * rows older than {@code history-retention}. {@code QueueMaintenanceService} carries
 * {@code @ConditionalOnProperty(jobs-enabled)} (disabled in the IT base), so the
 * unit is assembled by hand from real beans rather than autowired.
 */
class QueueMaintenancePruneEnvHealthIT extends AbstractPostgresIT {

    @Autowired private RunQueueRepository runQueueRepository;
    @Autowired private ScheduleFireLedger scheduleFireLedger;
    @Autowired private CiIdempotencyRepository ciIdempotencyRepository;
    @Autowired private WebhookDeliveryRepository webhookDeliveryRepository;
    @Autowired private EnvironmentHealthRepository environmentHealthRepository;
    @Autowired private SchedulingProperties schedulingProperties;
    @Autowired private CiProperties ciProperties;
    @Autowired private WebhookProperties webhookProperties;
    @Autowired private EnvironmentHealthProperties environmentHealthProperties;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void prune_oldHealthChecks_areDeleted() {
        var maintenance = new QueueMaintenanceService(runQueueRepository, scheduleFireLedger,
            ciIdempotencyRepository, webhookDeliveryRepository, environmentHealthRepository,
            schedulingProperties, ciProperties, webhookProperties, environmentHealthProperties);

        UUID org = ItFixtures.insertOrg(jdbc);
        UUID project = ItFixtures.insertProject(jdbc, org);
        UUID env = ItFixtures.insertEnvironment(jdbc, org, project, "STAGING", "https://staging.example.com");
        insertCheck(org, env, project, Instant.now().minus(Duration.ofDays(30)));
        insertCheck(org, env, project, Instant.now());

        maintenance.prune();

        Integer remaining = jdbc.queryForObject(
            "SELECT count(*) FROM environment_health_check WHERE environment_id = ?", Integer.class, env);
        assertThat(remaining).isEqualTo(1);
    }

    private void insertCheck(UUID org, UUID env, UUID project, Instant checkedAt) {
        jdbc.update("INSERT INTO environment_health_check "
            + "(org_id, environment_id, project_id, checked_at, health_status) "
            + "VALUES (?, ?, ?, ?, 'HEALTHY')", org, env, project, Timestamp.from(checkedAt));
    }
}
