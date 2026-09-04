package com.qualityops.api.execution.adapter.in.messaging;

import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RunLifecycleConsumerTest {

    @Mock
    private ApplyRunLifecycleUseCase useCase;

    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @Test
    void onRunStarted_event_delegatesToUseCase() {
        var consumer = new RunLifecycleConsumer(useCase);
        var event = new RunStartedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, UUID.randomUUID(),
            Instant.now(), RunStartedEvent.SCHEMA_VERSION);

        consumer.onRunStarted(event);

        verify(useCase).onRunStarted(event);
    }

    @Test
    void onRunCompleted_event_delegatesToUseCase() {
        var consumer = new RunLifecycleConsumer(useCase);
        var event = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, UUID.randomUUID(),
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
            RunOutcome.PASSED, List.of(), null);

        consumer.onRunCompleted(event);

        verify(useCase).onRunCompleted(event);
    }

    @Test
    void onRunFailed_event_delegatesToUseCase() {
        var consumer = new RunLifecycleConsumer(useCase);
        var event = new RunFailedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, UUID.randomUUID(),
            Instant.now(), RunFailedEvent.SCHEMA_VERSION, "reason");

        consumer.onRunFailed(event);

        verify(useCase).onRunFailed(event);
    }
}
