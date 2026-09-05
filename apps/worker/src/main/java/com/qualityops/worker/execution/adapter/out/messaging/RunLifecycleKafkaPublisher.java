package com.qualityops.worker.execution.adapter.out.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunStartedEvent;
import com.qualityops.worker.execution.application.port.out.RunLifecyclePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RunLifecycleKafkaPublisher implements RunLifecyclePublisher {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleKafkaPublisher.class);
    private static final String RUNS_STARTED_TOPIC = "runs.started";
    private static final String RUNS_COMPLETED_TOPIC = "runs.completed";
    private static final String RUNS_FAILED_TOPIC = "runs.failed";
    private static final String RESULTS_CHUNK_TOPIC = "results.chunk";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RunLifecycleKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishRunStarted(RunStartedEvent event) {
        log.info("Publishing run started event for run {}", event.runId());
        send(RUNS_STARTED_TOPIC, event.runId(), event);
    }

    @Override
    public void publishRunCompleted(RunCompletedEvent event) {
        log.info("Publishing run completed event for run {} with outcome {}", event.runId(), event.outcome());
        send(RUNS_COMPLETED_TOPIC, event.runId(), event);
    }

    @Override
    public void publishRunFailed(RunFailedEvent event) {
        log.info("Publishing run failed event for run {}: {}", event.runId(), event.reason());
        send(RUNS_FAILED_TOPIC, event.runId(), event);
    }

    @Override
    public void publishResultChunk(ResultChunkEvent event) {
        log.debug("Publishing results.chunk for run {} case {} epoch {}",
            event.runId(), event.testCaseId(), event.attemptEpoch());
        send(RESULTS_CHUNK_TOPIC, event.runId(), event);
    }

    @Override
    public void republishTerminal(String topic, UUID runId, String json) {
        try {
            Object event = switch (topic) {
                case RUNS_COMPLETED_TOPIC -> objectMapper.readValue(json, RunCompletedEvent.class);
                case RUNS_FAILED_TOPIC -> objectMapper.readValue(json, RunFailedEvent.class);
                default -> throw new IllegalArgumentException("not a terminal topic: " + topic);
            };
            log.info("Re-publishing cached terminal event for run {} to {}", runId, topic);
            send(topic, runId, event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot deserialise cached terminal event for run " + runId, e);
        }
    }

    private void send(String topic, UUID runId, Object event) {
        kafkaTemplate.send(topic, runId.toString(), event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to {} for run {}", topic, runId, ex);
            }
        });
    }
}
