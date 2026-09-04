package com.qualityops.api.result.adapter.in.messaging;

import com.qualityops.api.result.application.port.in.GenerateResultsUseCase;
import com.qualityops.events.RunCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RunCompletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RunCompletedConsumer.class);

    private final GenerateResultsUseCase generateResultsUseCase;

    public RunCompletedConsumer(GenerateResultsUseCase generateResultsUseCase) {
        this.generateResultsUseCase = generateResultsUseCase;
    }

    @KafkaListener(topics = "runs.completed", groupId = "api-results")
    public void handle(RunCompletedEvent event) {
        log.info("Received run completed event for runId={}", event.runId());
        generateResultsUseCase.generateResults(event);
    }
}
