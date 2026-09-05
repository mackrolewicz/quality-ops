package com.qualityops.worker.execution.adapter.in.messaging;

import com.qualityops.events.RunCancelRequestedEvent;
import com.qualityops.worker.execution.application.CancellationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes the API's {@code runs.cancel} command (ADR-006 §5.4) into the
 *  in-memory CancellationRegistry. Best-effort: a run that has already finished
 *  or was never started here simply no-ops. */
@Component
public class RunCancelConsumer {

    private static final Logger log = LoggerFactory.getLogger(RunCancelConsumer.class);

    private final CancellationRegistry registry;

    public RunCancelConsumer(CancellationRegistry registry) {
        this.registry = registry;
    }

    @KafkaListener(topics = "runs.cancel", groupId = "worker-execution")
    public void handle(RunCancelRequestedEvent event) {
        log.info("Cancel requested for run {} execution {}", event.runId(), event.executionId());
        registry.markCancelled(event.executionId());
    }
}
