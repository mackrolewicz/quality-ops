package com.qualityops.api.scheduling.application.service;

import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunResult;
import com.qualityops.api.execution.domain.QueueState;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.domain.Project;
import com.qualityops.api.scheduling.application.port.out.ScheduleFireLedger;
import com.qualityops.api.scheduling.application.port.out.ScheduleRepository;
import com.qualityops.api.scheduling.domain.CatchUpPolicy;
import com.qualityops.api.scheduling.domain.Schedule;
import com.qualityops.api.scheduling.domain.ScheduleKind;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestSuite;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleFireServiceTest {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ScheduleFireLedger fireLedger;
    @Mock private EnqueueRunUseCase enqueueRunUseCase;
    @Mock private GetProjectUseCase getProjectUseCase;
    @Mock private GetTestSuiteUseCase getTestSuiteUseCase;
    @Mock private GetEnvironmentUseCase getEnvironmentUseCase;

    private ScheduleFireService service;
    private final CronCalculator cron = new CronCalculator();
    private final QueueMetrics metrics =
        new QueueMetrics(new SimpleMeterRegistry(), org.mockito.Mockito.mock(
            com.qualityops.api.execution.application.port.out.RunQueueRepository.class));

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();
    private final UUID createdBy = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ScheduleFireService(scheduleRepository, fireLedger, enqueueRunUseCase, cron,
            getProjectUseCase, getTestSuiteUseCase, getEnvironmentUseCase, metrics);
        var now = Instant.now();
        lenient().when(getProjectUseCase.getDomain(projectId, orgId))
            .thenReturn(new Project(projectId, orgId, "P", "d", "p", createdBy, now, now, null));
        lenient().when(getTestSuiteUseCase.getDomain(suiteId, orgId))
            .thenReturn(new TestSuite(suiteId, orgId, projectId, "S", "d", SuiteType.API, now, now, null));
        lenient().when(getEnvironmentUseCase.getDomain(envId, orgId))
            .thenReturn(new Environment(envId, orgId, projectId, "E", "https://e.test",
                EnvironmentType.DEV, EnvironmentStatus.ACTIVE, now, now, null));
    }

    private Schedule recurring(Instant nextFireAt, CatchUpPolicy policy) {
        var now = Instant.now();
        return new Schedule(UUID.randomUUID(), orgId, projectId, suiteId, envId, "nightly",
            ScheduleKind.RECURRING, "0 0 2 * * *", "UTC", null, RunPriority.NORMAL, policy,
            true, nextFireAt, null, null, null, createdBy, now, now);
    }

    @Test
    void fire_recurringOnTime_insertsLedgerAndEnqueuesOnce_thenAdvances() {
        var schedule = recurring(Instant.now().minusSeconds(5), CatchUpPolicy.SKIP_MISSED);
        when(fireLedger.tryInsert(eq(orgId), eq(schedule.id()), any())).thenReturn(true);
        when(enqueueRunUseCase.enqueue(any()))
            .thenReturn(new EnqueueRunResult(UUID.randomUUID(), UUID.randomUUID(), QueueState.QUEUED));

        service.fire(schedule);

        verify(enqueueRunUseCase).enqueue(any());
        verify(fireLedger).attachRun(eq(schedule.id()), any(), any());
        verify(scheduleRepository).advanceNextFireAt(eq(schedule.id()), eq(orgId), any(), any());
    }

    @Test
    void fire_ledgerInsertReturnsFalse_skipsEnqueueButStillAdvances() {
        var schedule = recurring(Instant.now().minusSeconds(5), CatchUpPolicy.SKIP_MISSED);
        when(fireLedger.tryInsert(any(), any(), any())).thenReturn(false);

        service.fire(schedule);

        verify(enqueueRunUseCase, never()).enqueue(any());
        verify(scheduleRepository).advanceNextFireAt(eq(schedule.id()), eq(orgId), any(), any());
    }

    @Test
    void fire_recurringMissedWindow_skipMissed_enqueuesNothing() {
        // next_fire_at far in the past AND the following occurrence is also < now
        var schedule = recurring(Instant.now().minus(java.time.Duration.ofDays(3)),
            CatchUpPolicy.SKIP_MISSED);

        service.fire(schedule);

        verify(enqueueRunUseCase, never()).enqueue(any());
        verify(fireLedger, never()).tryInsert(any(), any(), any());
        verify(scheduleRepository).advanceNextFireAt(eq(schedule.id()), eq(orgId), any(), any());
    }

    @Test
    void fire_recurringMissedWindow_fireOnce_enqueuesExactlyOneMakeUp() {
        var schedule = recurring(Instant.now().minus(java.time.Duration.ofDays(3)),
            CatchUpPolicy.FIRE_ONCE);
        when(fireLedger.tryInsert(any(), any(), any())).thenReturn(true);
        when(enqueueRunUseCase.enqueue(any()))
            .thenReturn(new EnqueueRunResult(UUID.randomUUID(), UUID.randomUUID(), QueueState.QUEUED));

        service.fire(schedule);

        verify(enqueueRunUseCase).enqueue(any());
    }

    @Test
    void fire_oneTime_marksScheduleFiredAndDisabled() {
        var now = Instant.now();
        var oneTime = new Schedule(UUID.randomUUID(), orgId, projectId, suiteId, envId, "once",
            ScheduleKind.ONE_TIME, null, null, now.minusSeconds(1), RunPriority.HIGH,
            CatchUpPolicy.SKIP_MISSED, true, now.minusSeconds(1), null, null, null, createdBy, now, now);
        when(fireLedger.tryInsert(any(), any(), any())).thenReturn(true);
        when(enqueueRunUseCase.enqueue(any()))
            .thenReturn(new EnqueueRunResult(UUID.randomUUID(), UUID.randomUUID(), QueueState.QUEUED));

        service.fire(oneTime);

        verify(scheduleRepository).markOneTimeFired(eq(oneTime.id()), eq(orgId), any());
        verify(scheduleRepository, never()).advanceNextFireAt(any(), any(), any(), any());
    }

    @Test
    void fire_targetNoLongerResolves_abandonsSchedule_noEnqueue() {
        var schedule = recurring(Instant.now().minusSeconds(5), CatchUpPolicy.SKIP_MISSED);
        when(getTestSuiteUseCase.getDomain(suiteId, orgId))
            .thenThrow(new com.qualityops.api.testsuite.exception.TestSuiteNotFoundException("gone"));

        service.fire(schedule);

        verify(scheduleRepository).abandon(eq(schedule.id()), eq(orgId), any(), any());
        verify(enqueueRunUseCase, never()).enqueue(any());
    }
}
