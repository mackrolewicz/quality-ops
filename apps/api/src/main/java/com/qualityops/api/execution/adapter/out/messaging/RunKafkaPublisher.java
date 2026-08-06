package com.qualityops.api.execution.adapter.out.messaging;

import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.event.RunCompletedEvent;
import com.qualityops.api.execution.event.RunRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RunKafkaPublisher implements RunEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RunKafkaPublisher.class);
    private static final String RUNS_REQUESTED_TOPIC = "runs.requested";
    private static final String RUNS_COMPLETED_TOPIC = "runs.completed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public RunKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishRunRequested(RunRequestedEvent event) {
        log.info("Publishing run requested event for run {}", event.runId());
        kafkaTemplate.send(RUNS_REQUESTED_TOPIC, event.runId().toString(), event);
    }

    @Override
    public void publishRunCompleted(RunCompletedEvent event) {
        log.info("Publishing run completed event for run {} with status {}", event.runId(), event.status());
        kafkaTemplate.send(RUNS_COMPLETED_TOPIC, event.runId().toString(), event);
    }
}
