package com.qualityops.worker.execution.adapter.in.messaging;

import com.qualityops.events.RunCancelRequestedEvent;
import com.qualityops.worker.config.CancellationProperties;
import com.qualityops.worker.execution.application.CancellationRegistry;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunCancelConsumerTest {

    private final CancellationRegistry registry = new CancellationRegistry(
        new CancellationProperties(null),
        TestProps.defaults(com.qualityops.worker.config.WorkerExecutionProperties.Mode.AUTO,
            java.time.Duration.ofMinutes(5)));
    private final RunCancelConsumer consumer = new RunCancelConsumer(registry);

    @Test
    void handle_marksExecutionCancelledInRegistry() {
        var executionId = UUID.randomUUID();
        var event = new RunCancelRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), executionId, Instant.now(), RunCancelRequestedEvent.SCHEMA_VERSION);

        consumer.handle(event);

        assertThat(registry.isCancelled(executionId)).isTrue();
    }

    @Test
    void isCancelled_unknownExecution_returnsFalse() {
        assertThat(registry.isCancelled(UUID.randomUUID())).isFalse();
    }
}
