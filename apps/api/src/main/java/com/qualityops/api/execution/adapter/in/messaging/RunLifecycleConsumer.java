package com.qualityops.api.execution.adapter.in.messaging;

import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * API-side back-consumer for Worker lifecycle events (group {@code api-execution}).
 * Distinct from the {@code api-results} group that generates result rows on the
 * same {@code runs.completed} topic — this fan-out is intentional.
 */
@Component
public class RunLifecycleConsumer {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleConsumer.class);

    private final ApplyRunLifecycleUseCase applyRunLifecycleUseCase;

    public RunLifecycleConsumer(ApplyRunLifecycleUseCase applyRunLifecycleUseCase) {
        this.applyRunLifecycleUseCase = applyRunLifecycleUseCase;
    }

    @KafkaListener(topics = "runs.started", groupId = "api-execution")
    public void onRunStarted(RunStartedEvent event) {
        log.info("Received run started event for runId={}", event.runId());
        applyRunLifecycleUseCase.onRunStarted(event);
    }

    @KafkaListener(topics = "runs.completed", groupId = "api-execution")
    public void onRunCompleted(RunCompletedEvent event) {
        log.info("Received run completed event for runId={}", event.runId());
        applyRunLifecycleUseCase.onRunCompleted(event);
    }

    @KafkaListener(topics = "runs.failed", groupId = "api-execution")
    public void onRunFailed(RunFailedEvent event) {
        log.info("Received run failed event for runId={}", event.runId());
        applyRunLifecycleUseCase.onRunFailed(event);
    }
}
