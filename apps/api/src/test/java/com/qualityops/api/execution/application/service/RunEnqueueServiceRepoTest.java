package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.application.port.out.RunQueueRepository.EnqueueRow;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.domain.Project;
import com.qualityops.api.scm.application.port.in.RepositoryRunFrozen;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase.ResolvedRepositoryRun;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.domain.RepoTestSpec;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.events.FrameworkPreset;
import com.qualityops.events.RepoNetworkPolicy;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepoReportFormat;
import com.qualityops.events.RepoResourceProfile;
import com.qualityops.events.RepoTestSnapshot;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.RepositoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-009 §4 — the enqueue path for a suite that contains a repository case:
 *  preflight runs once <em>before</em> the run row exists, the frozen SHA lands
 *  in {@code run_queue.requested_event_json}, and a preflight failure aborts the
 *  whole admission (no {@code test_runs} / {@code run_queue} write). */
@ExtendWith(MockitoExtension.class)
class RunEnqueueServiceRepoTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String IMAGE =
        "python:3.12-slim@sha256:2222222222222222222222222222222222222222222222222222222222222222";

    @Mock private RunRepository runRepository;
    @Mock private RunQueueRepository runQueueRepository;
    @Mock private GetProjectUseCase getProjectUseCase;
    @Mock private GetTestSuiteUseCase getTestSuiteUseCase;
    @Mock private ListTestCasesForSuiteUseCase listTestCasesForSuiteUseCase;
    @Mock private GetEnvironmentUseCase getEnvironmentUseCase;
    @Mock private ResolveRepositoryRunUseCase resolveRepositoryRunUseCase;
    @Mock private RepositoryRunWriteUseCase repositoryRunWriteUseCase;

    private RunEnqueueService service;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID triggeredBy = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID repoCaseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunEnqueueService(runRepository, runQueueRepository, getProjectUseCase,
            getTestSuiteUseCase, listTestCasesForSuiteUseCase, getEnvironmentUseCase,
            new RunEventMapper(), resolveRepositoryRunUseCase, repositoryRunWriteUseCase, objectMapper);
    }

    private EnqueueRunCommand cmd() {
        return new EnqueueRunCommand(orgId, projectId, suiteId, environmentId, triggeredBy,
            RunPriority.NORMAL, RunSource.MANUAL, null);
    }

    private void stubGraph(List<TestCase> cases) {
        var now = Instant.now();
        when(getProjectUseCase.getDomain(projectId, orgId))
            .thenReturn(new Project(projectId, orgId, "P", "desc", "p", triggeredBy, now, now, null));
        when(getTestSuiteUseCase.getDomain(suiteId, orgId))
            .thenReturn(new TestSuite(suiteId, orgId, projectId, "S", "d", SuiteType.API, now, now, null));
        when(getEnvironmentUseCase.getDomain(environmentId, orgId)).thenReturn(new Environment(
            environmentId, orgId, projectId, "E", "https://e.test", EnvironmentType.PRODUCTION,
            EnvironmentStatus.ACTIVE, now, now, null));
        when(listTestCasesForSuiteUseCase.listAllForSuite(suiteId, orgId)).thenReturn(cases);
        lenient().when(runRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TestCase repoCase() {
        var spec = new RepoTestSpec(connectionId, "main", "PYTEST", null,
            List.of("pytest", "--junitxml=report.xml"), "JUNIT_XML", List.of("report.xml"), List.of(),
            List.of(), List.of(), null, null, null);
        var now = Instant.now();
        return new TestCase(repoCaseId, orgId, suiteId, "Repo case", "d", 0, null, null, spec, now, now, null);
    }

    private TestCase plainCase() {
        var now = Instant.now();
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Plain", "d", 1, null, null, now, now, null);
    }

    private RepoTestSnapshot frozenSnapshot(String sha) {
        return new RepoTestSnapshot(connectionId, RepositoryProvider.GITHUB, "github.com", "acme/web",
            "main", sha, RepoRefType.BRANCH, FrameworkPreset.PYTEST, IMAGE, null,
            List.of("pytest", "--junitxml=report.xml"), RepoReportFormat.JUNIT_XML, List.of("report.xml"),
            List.of(), List.of(), List.of(), null, RepoResourceProfile.SMALL, RepoNetworkPolicy.ISOLATED, 600);
    }

    private ResolvedRepositoryRun resolved(String sha) {
        var s = frozenSnapshot(sha);
        return new ResolvedRepositoryRun(s, RepositoryRunFrozen.fromSnapshot(s));
    }

    private RunRequestedEvent capturedEvent() throws Exception {
        var captor = ArgumentCaptor.forClass(EnqueueRow.class);
        verify(runQueueRepository).enqueue(captor.capture());
        return objectMapper.readValue(captor.getValue().requestedEventJson(), RunRequestedEvent.class);
    }

    @Test
    void enqueue_repoCase_preflightsOnce_thenStagesFrozenRow_andFreezesShaInEvent() throws Exception {
        stubGraph(List.of(repoCase()));
        when(resolveRepositoryRunUseCase.resolve(any())).thenReturn(resolved(SHA));

        var result = service.enqueue(cmd());

        verify(resolveRepositoryRunUseCase, times(1)).resolve(any());
        verify(repositoryRunWriteUseCase).stageFrozen(eq(result.runId()), eq(orgId),
            any(RepositoryRunFrozen.class));
        var event = capturedEvent();
        assertThat(event.testCases()).singleElement().satisfies(tc -> {
            assertThat(tc.repoTest()).isNotNull();
            assertThat(tc.repoTest().commitSha()).isEqualTo(SHA);
            assertThat(tc.repoTest().runnerImageRef()).contains("@sha256:");
        });
    }

    @Test
    void enqueue_preflightUnresolvable_abortsBeforeAnyWrite() {
        stubGraph(List.of(repoCase()));
        when(resolveRepositoryRunUseCase.resolve(any()))
            .thenThrow(new RepositoryRefUnresolvableException("no such ref"));

        assertThatThrownBy(() -> service.enqueue(cmd()))
            .isInstanceOf(RepositoryRefUnresolvableException.class);

        verify(runRepository, never()).save(any());
        verify(runQueueRepository, never()).enqueue(any());
        verify(repositoryRunWriteUseCase, never()).stageFrozen(any(), any(), any());
    }

    @Test
    void enqueue_repoCaseMixedWithOtherCases_rejectedAsNotSoleCase() {
        stubGraph(List.of(repoCase(), plainCase()));

        assertThatThrownBy(() -> service.enqueue(cmd()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("REPOSITORY_CASE_MUST_BE_SOLE_CASE");

        verify(runRepository, never()).save(any());
        verify(resolveRepositoryRunUseCase, never()).resolve(any());
    }
}
