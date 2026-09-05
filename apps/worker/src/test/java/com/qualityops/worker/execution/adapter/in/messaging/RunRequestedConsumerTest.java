package com.qualityops.worker.execution.adapter.in.messaging;

import com.qualityops.events.RunRequestedEvent;
import com.qualityops.worker.execution.application.port.in.ProcessRunRequestedUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RunRequestedConsumerTest {

    @Mock
    private ProcessRunRequestedUseCase useCase;

    @Test
    void handle_runRequestedEvent_delegatesToUseCase() {
        var consumer = new RunRequestedConsumer(useCase);
        var event = new RunRequestedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of());

        consumer.handle(event);

        verify(useCase).processRunRequested(event);
    }
}
