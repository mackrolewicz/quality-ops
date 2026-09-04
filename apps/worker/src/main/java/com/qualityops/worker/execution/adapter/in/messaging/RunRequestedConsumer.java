package com.qualityops.worker.execution.adapter.in.messaging;

import com.qualityops.events.RunRequestedEvent;
import com.qualityops.worker.execution.application.port.in.ProcessRunRequestedUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RunRequestedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RunRequestedConsumer.class);

    private final ProcessRunRequestedUseCase processRunRequestedUseCase;

    public RunRequestedConsumer(ProcessRunRequestedUseCase processRunRequestedUseCase) {
        this.processRunRequestedUseCase = processRunRequestedUseCase;
    }

    @KafkaListener(topics = "runs.requested", groupId = "worker-execution")
    public void handle(RunRequestedEvent event) {
        log.info("Received run requested event for runId={}", event.runId());
        processRunRequestedUseCase.processRunRequested(event);
    }
}
