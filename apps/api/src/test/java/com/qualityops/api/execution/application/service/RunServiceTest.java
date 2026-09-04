package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunResult;
import com.qualityops.api.execution.application.port.out.RepositoryRunRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.dto.CreateRunRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock
    private RunRepository runRepository;

    @Mock
    private EnqueueRunUseCase enqueueRunUseCase;

    @Mock
    private RunQueueRepository runQueueRepository;

    @Mock
    private RepositoryRunRepository repositoryRunRepository;

    private RunService runService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID triggeredBy = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runService = new RunService(runRepository, enqueueRunUseCase, runQueueRepository,
            repositoryRunRepository);
    }

    @Test
    void trigger_delegatesToEnqueue_andReturnsQueuedResponse() {
        var runId = UUID.randomUUID();
        var execId = UUID.randomUUID();
        when(enqueueRunUseCase.enqueue(any(EnqueueRunCommand.class)))
            .thenReturn(new EnqueueRunResult(runId, execId, QueueState.QUEUED));
        when(runRepository.findByIdAndOrgId(runId, orgId)).thenReturn(Optional.of(pendingRun(runId)));

        var response = runService.trigger(
            new CreateRunRequest(projectId, suiteId, environmentId, null), orgId, triggeredBy);

        var captor = ArgumentCaptor.forClass(EnqueueRunCommand.class);
        verify(enqueueRunUseCase).enqueue(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.source()).isEqualTo(RunSource.MANUAL);
        assertThat(cmd.priority()).isEqualTo(RunPriority.NORMAL);
        assertThat(cmd.scheduleId()).isNull();
        assertThat(response.queueState()).isEqualTo(QueueState.QUEUED);
        assertThat(response.priority()).isEqualTo(RunPriority.NORMAL);
        assertThat(response.cancelRequested()).isFalse();
        assertThat(response.status()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void trigger_priorityHigh_passesHighToEnqueue() {
        var runId = UUID.randomUUID();
        when(enqueueRunUseCase.enqueue(any(EnqueueRunCommand.class)))
            .thenReturn(new EnqueueRunResult(runId, UUID.randomUUID(), QueueState.QUEUED));
        when(runRepository.findByIdAndOrgId(runId, orgId)).thenReturn(Optional.of(pendingRun(runId)));

        var response = runService.trigger(
            new CreateRunRequest(projectId, suiteId, environmentId, "HIGH"), orgId, triggeredBy);

        var captor = ArgumentCaptor.forClass(EnqueueRunCommand.class);
        verify(enqueueRunUseCase).enqueue(captor.capture());
        assertThat(captor.getValue().priority()).isEqualTo(RunPriority.HIGH);
        assertThat(response.priority()).isEqualTo(RunPriority.HIGH);
    }

    @Test
    void getStats_delegatesToRepository() {
        var since = Instant.now().minusSeconds(3600);
        var stats = new RunStats(10L, 8L, 2L);
        when(runRepository.getStats(projectId, orgId, since)).thenReturn(stats);

        var result = runService.getStats(projectId, orgId, since);

        assertThat(result).isEqualTo(stats);
        verify(runRepository).getStats(projectId, orgId, since);
    }

    private TestRun pendingRun(UUID runId) {
        return new TestRun(runId, orgId, projectId, suiteId, environmentId, UUID.randomUUID(),
            RunStatus.PENDING, triggeredBy, new RunConfigSnapshot(List.of()), null, null, Instant.now());
    }
}
