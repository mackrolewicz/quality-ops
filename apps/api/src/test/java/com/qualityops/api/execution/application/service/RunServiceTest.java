package com.qualityops.api.execution.application.service;

import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.execution.application.port.out.RunEventPublisher;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.event.RunCompletedEvent;
import com.qualityops.api.execution.event.RunRequestedEvent;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.domain.Project;
import com.qualityops.api.project.exception.ProjectNotFoundException;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock
    private RunRepository runRepository;

    @Mock
    private RunEventPublisher runEventPublisher;

    @Mock
    private GetProjectUseCase getProjectUseCase;

    @Mock
    private GetTestSuiteUseCase getTestSuiteUseCase;

    @Mock
    private ListTestCasesForSuiteUseCase listTestCasesForSuiteUseCase;

    @Mock
    private GetEnvironmentUseCase getEnvironmentUseCase;

    private RunService runService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID triggeredBy = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runService = new RunService(runRepository, runEventPublisher, getProjectUseCase,
            getTestSuiteUseCase, listTestCasesForSuiteUseCase, getEnvironmentUseCase);
    }

    @Test
    void trigger_valid_savesRunPendingThenPublishesRunRequested() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(getEnvironmentUseCase.getDomain(environmentId, orgId)).thenReturn(existingEnvironment());
        when(listTestCasesForSuiteUseCase.listAllForSuite(suiteId, orgId)).thenReturn(List.of(existingCase()));
        when(runRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateRunRequest(projectId, suiteId, environmentId);

        var response = runService.trigger(request, orgId, triggeredBy);

        var captor = ArgumentCaptor.forClass(TestRun.class);
        verify(runRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.orgId()).isEqualTo(orgId);
        assertThat(saved.projectId()).isEqualTo(projectId);
        assertThat(saved.suiteId()).isEqualTo(suiteId);
        assertThat(saved.environmentId()).isEqualTo(environmentId);
        assertThat(saved.status()).isEqualTo(RunStatus.PENDING);
        assertThat(saved.triggeredBy()).isEqualTo(triggeredBy);
        assertThat(saved.configSnapshot().testCases()).hasSize(1);
        assertThat(response.status()).isEqualTo(RunStatus.PENDING);

        InOrder inOrderVerifier = inOrder(runRepository, runEventPublisher);
        inOrderVerifier.verify(runRepository).save(any(TestRun.class));
        inOrderVerifier.verify(runEventPublisher).publishRunRequested(any(RunRequestedEvent.class));
    }

    @Test
    void trigger_projectNotFound_throwsAndNeverSavesOrPublishes() {
        when(getProjectUseCase.getDomain(projectId, orgId))
            .thenThrow(new ProjectNotFoundException("Project not found: " + projectId));
        var request = new CreateRunRequest(projectId, suiteId, environmentId);

        assertThatThrownBy(() -> runService.trigger(request, orgId, triggeredBy))
            .isInstanceOf(ProjectNotFoundException.class);

        verify(runRepository, never()).save(any(TestRun.class));
        verify(runEventPublisher, never()).publishRunRequested(any(RunRequestedEvent.class));
    }

    @Test
    void trigger_suiteBelongsToDifferentProject_throwsTestSuiteNotFoundException() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        var suiteInOtherProject = new TestSuite(suiteId, orgId, UUID.randomUUID(), "Smoke", "desc",
            SuiteType.API, Instant.now(), Instant.now(), null);
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(suiteInOtherProject);
        var request = new CreateRunRequest(projectId, suiteId, environmentId);

        assertThatThrownBy(() -> runService.trigger(request, orgId, triggeredBy))
            .isInstanceOf(TestSuiteNotFoundException.class);

        verify(runRepository, never()).save(any(TestRun.class));
        verify(runEventPublisher, never()).publishRunRequested(any(RunRequestedEvent.class));
    }

    @Test
    void trigger_environmentBelongsToDifferentProject_throwsEnvironmentNotFoundException() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        var environmentInOtherProject = new Environment(environmentId, orgId, UUID.randomUUID(), "Prod",
            "https://example.com", EnvironmentType.PRODUCTION, EnvironmentStatus.ACTIVE,
            Instant.now(), Instant.now(), null);
        when(getEnvironmentUseCase.getDomain(environmentId, orgId)).thenReturn(environmentInOtherProject);
        var request = new CreateRunRequest(projectId, suiteId, environmentId);

        assertThatThrownBy(() -> runService.trigger(request, orgId, triggeredBy))
            .isInstanceOf(EnvironmentNotFoundException.class);

        verify(runRepository, never()).save(any(TestRun.class));
        verify(runEventPublisher, never()).publishRunRequested(any(RunRequestedEvent.class));
    }

    @Test
    void processRunRequested_pendingRun_transitionsToRunningThenResolvedAndPublishesCompleted() {
        var event = existingRunRequestedEvent();
        when(runRepository.transitionStatus(eq(event.runId()), eq(RunStatus.PENDING), eq(RunStatus.RUNNING), any(Instant.class)))
            .thenReturn(true);
        when(runRepository.transitionStatus(eq(event.runId()), eq(RunStatus.RUNNING), any(RunStatus.class), any(Instant.class)))
            .thenReturn(true);
        when(runRepository.findByIdAndOrgId(event.runId(), event.orgId()))
            .thenReturn(java.util.Optional.of(persistedRun(event)));

        runService.processRunRequested(event);

        verify(runRepository, times(1))
            .transitionStatus(eq(event.runId()), eq(RunStatus.PENDING), eq(RunStatus.RUNNING), any(Instant.class));
        verify(runRepository, times(1))
            .transitionStatus(eq(event.runId()), eq(RunStatus.RUNNING), any(RunStatus.class), any(Instant.class));
        var captor = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(runEventPublisher, times(1)).publishRunCompleted(captor.capture());
        assertThat(captor.getValue().testCases()).hasSize(1);
    }

    @Test
    void processRunRequested_duplicateDelivery_alreadyRunningOrResolved_isNoOpAndNeverPublishes() {
        var event = existingRunRequestedEvent();
        when(runRepository.transitionStatus(eq(event.runId()), eq(RunStatus.PENDING), eq(RunStatus.RUNNING), any(Instant.class)))
            .thenReturn(false);

        runService.processRunRequested(event);

        verify(runRepository, never())
            .transitionStatus(eq(event.runId()), eq(RunStatus.RUNNING), any(RunStatus.class), any(Instant.class));
        verify(runEventPublisher, never()).publishRunCompleted(any(RunCompletedEvent.class));
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

    private RunRequestedEvent existingRunRequestedEvent() {
        return new RunRequestedEvent(UUID.randomUUID(), orgId, projectId, suiteId, environmentId,
            triggeredBy, Instant.now());
    }

    private TestRun persistedRun(RunRequestedEvent event) {
        var now = Instant.now();
        var snapshot = new com.qualityops.api.execution.domain.RunConfigSnapshot(
            List.of(new com.qualityops.api.execution.domain.TestCaseSnapshotItem(UUID.randomUUID(), "Login works", 1)));
        return new TestRun(event.runId(), orgId, projectId, suiteId, environmentId, RunStatus.RUNNING,
            triggeredBy, snapshot, now, null, now);
    }

    private Project existingProject() {
        var now = Instant.now();
        return new Project(projectId, orgId, "Demo", "desc", "demo-slug", UUID.randomUUID(), now, now, null);
    }

    private TestSuite existingSuite() {
        var now = Instant.now();
        return new TestSuite(suiteId, orgId, projectId, "Smoke", "desc", SuiteType.API, now, now, null);
    }

    private Environment existingEnvironment() {
        var now = Instant.now();
        return new Environment(environmentId, orgId, projectId, "Prod", "https://example.com",
            EnvironmentType.PRODUCTION, EnvironmentStatus.ACTIVE, now, now, null);
    }

    private TestCase existingCase() {
        var now = Instant.now();
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Login works", "desc", 1, now, now, null);
    }
}
