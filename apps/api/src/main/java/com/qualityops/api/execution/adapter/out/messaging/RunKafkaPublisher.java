package com.qualityops.api.execution.adapter.out.messaging;

import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.exception.RunEventPublishException;
import com.qualityops.events.RunCancelRequestedEvent;
import com.qualityops.events.RunRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RunKafkaPublisher implements RunEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RunKafkaPublisher.class);
    private static final String RUNS_REQUESTED_TOPIC = "runs.requested";
    private static final String RUNS_CANCEL_TOPIC = "runs.cancel";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration sendTimeout;

    public RunKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate, SchedulingProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = props.queue().sendTimeout();
    }

    @Override
    public void publishRunRequested(RunRequestedEvent event) {
        log.info("Publishing run requested event for run {}", event.runId());
        try {
            kafkaTemplate.send(RUNS_REQUESTED_TOPIC, event.runId().toString(), event)
                .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunEventPublishException(
                "interrupted publishing runs.requested for " + event.runId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RunEventPublishException(
                "failed publishing runs.requested for " + event.runId(), e);
        }
    }

    @Override
    public void publishRunCancelRequested(RunCancelRequestedEvent event) {
        log.info("Publishing run cancel requested for run {}", event.runId());
        kafkaTemplate.send(RUNS_CANCEL_TOPIC, event.runId().toString(), event);
    }
}
