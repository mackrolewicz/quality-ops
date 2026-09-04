package com.qualityops.api.scheduling.application.service;

import com.qualityops.api.config.CiProperties;
import com.qualityops.api.config.EnvironmentHealthProperties;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.config.WebhookProperties;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository;
import com.qualityops.api.execution.application.port.out.CiIdempotencyRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.scheduling.application.port.out.ScheduleFireLedger;
import com.qualityops.api.webhook.application.port.out.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "qualityops.scheduling.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class QueueMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(QueueMaintenanceService.class);

    private final RunQueueRepository runQueueRepository;
    private final ScheduleFireLedger scheduleFireLedger;
    private final CiIdempotencyRepository ciIdempotencyRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final EnvironmentHealthRepository environmentHealthRepository;
    private final SchedulingProperties props;
    private final CiProperties ciProps;
    private final WebhookProperties webhookProps;
    private final EnvironmentHealthProperties envHealthProps;

    public QueueMaintenanceService(RunQueueRepository runQueueRepository,
                                   ScheduleFireLedger scheduleFireLedger,
                                   CiIdempotencyRepository ciIdempotencyRepository,
                                   WebhookDeliveryRepository webhookDeliveryRepository,
                                   EnvironmentHealthRepository environmentHealthRepository,
                                   SchedulingProperties props,
                                   CiProperties ciProps,
                                   WebhookProperties webhookProps,
                                   EnvironmentHealthProperties envHealthProps) {
        this.runQueueRepository = runQueueRepository;
        this.scheduleFireLedger = scheduleFireLedger;
        this.ciIdempotencyRepository = ciIdempotencyRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.environmentHealthRepository = environmentHealthRepository;
        this.props = props;
        this.ciProps = ciProps;
        this.webhookProps = webhookProps;
        this.envHealthProps = envHealthProps;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void prune() {
        var now = Instant.now();
        int q = runQueueRepository.deleteTerminalOlderThan(now.minus(props.queue().retention()));
        int f = scheduleFireLedger.deleteOlderThan(now.minus(props.fireLedgerRetention()));
        int c = ciIdempotencyRepository.deleteOlderThan(now.minus(ciProps.idempotencyRetention()));
        int w = webhookDeliveryRepository.deleteTerminalOlderThan(
            now.minus(webhookProps.deliveryRetention()));
        int e = environmentHealthRepository.deleteChecksOlderThan(
            now.minus(envHealthProps.historyRetention()));
        if (q > 0 || f > 0 || c > 0 || w > 0 || e > 0) {
            log.info("Queue maintenance pruned {} run_queue, {} schedule_fire, {} ci_idempotency_key, "
                + "{} webhook_delivery, {} environment_health_check rows", q, f, c, w, e);
        }
    }
}
