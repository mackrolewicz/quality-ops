package com.qualityops.api.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
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
import com.qualityops.api.project.exception.ProjectNotFoundException;
import com.qualityops.api.scm.application.port.in.ResolveRepositoryRunUseCase;
import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.in.ListTestCasesForSuiteUseCase;
import com.qualityops.api.testsuite.domain.ApiRequestSpec;
import com.qualityops.api.testsuite.domain.BrowserTestSpec;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunEnqueueServiceTest {

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

    @BeforeEach
    void setUp() {
        service = new RunEnqueueService(runRepository, runQueueRepository, getProjectUseCase,
            getTestSuiteUseCase, listTestCasesForSuiteUseCase, getEnvironmentUseCase,
            new RunEventMapper(), resolveRepositoryRunUseCase, repositoryRunWriteUseCase, objectMapper);
    }

    private EnqueueRunCommand cmd(RunPriority priority) {
        return new EnqueueRunCommand(orgId, projectId, suiteId, environmentId, triggeredBy,
            priority, RunSource.MANUAL, null);
    }

    private void stubHappyPath(List<TestCase> cases) {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(getEnvironmentUseCase.getDomain(environmentId, orgId)).thenReturn(existingEnvironment());
        when(listTestCasesForSuiteUseCase.listAllForSuite(suiteId, orgId)).thenReturn(cases);
        when(runRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EnqueueRow captureEnqueued() {
        var captor = ArgumentCaptor.forClass(EnqueueRow.class);
        verify(runQueueRepository).enqueue(captor.capture());
        return captor.getValue();
    }

    @Test
    void enqueue_valid_savesRunPendingThenEnqueuesQueuedRow_noPublish() {
        stubHappyPath(List.of(existingCase()));

        var result = service.enqueue(cmd(RunPriority.NORMAL));

        var runCaptor = ArgumentCaptor.forClass(TestRun.class);
        verify(runRepository).save(runCaptor.capture());
        var saved = runCaptor.getValue();
        assertThat(saved.orgId()).isEqualTo(orgId);
        assertThat(saved.status().name()).isEqualTo("PENDING");
        assertThat(result.runId()).isEqualTo(saved.id());
        assertThat(result.executionId()).isEqualTo(saved.executionId());

        InOrder order = inOrder(runRepository, runQueueRepository);
        order.verify(runRepository).save(any(TestRun.class));
        order.verify(runQueueRepository).enqueue(any(EnqueueRow.class));
    }

    @Test
    void enqueue_valid_freezesRunRequestedEventWithSnapshotAndEnvelopeInJson() {
        var testCase = existingCase();
        stubHappyPath(List.of(testCase));

        service.enqueue(cmd(RunPriority.HIGH));

        var row = captureEnqueued();
        assertThat(row.priority()).isEqualTo(RunPriority.HIGH);
        assertThat(row.orgId()).isEqualTo(orgId);
        assertThat(row.requestedEventJson()).contains(testCase.id().toString());
        assertThat(row.requestedEventJson()).contains("\"schemaVersion\":5");
        assertThat(row.requestedEventJson()).contains(triggeredBy.toString());
    }

    @Test
    void enqueue_projectNotFound_throwsAndNeverSavesOrEnqueues() {
        when(getProjectUseCase.getDomain(projectId, orgId))
            .thenThrow(new ProjectNotFoundException("Project not found: " + projectId));

        assertThatThrownBy(() -> service.enqueue(cmd(RunPriority.NORMAL)))
            .isInstanceOf(ProjectNotFoundException.class);

        verify(runRepository, never()).save(any(TestRun.class));
        verify(runQueueRepository, never()).enqueue(any(EnqueueRow.class));
    }

    @Test
    void enqueue_suiteBelongsToDifferentProject_throwsTestSuiteNotFound() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(new TestSuite(
            suiteId, orgId, UUID.randomUUID(), "Smoke", "desc", SuiteType.API,
            Instant.now(), Instant.now(), null));

        assertThatThrownBy(() -> service.enqueue(cmd(RunPriority.NORMAL)))
            .isInstanceOf(TestSuiteNotFoundException.class);

        verify(runQueueRepository, never()).enqueue(any(EnqueueRow.class));
    }

    @Test
    void enqueue_environmentBelongsToDifferentProject_throwsEnvironmentNotFound() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(existingSuite());
        when(getEnvironmentUseCase.getDomain(environmentId, orgId)).thenReturn(new Environment(
            environmentId, orgId, UUID.randomUUID(), "Prod", "https://example.com",
            EnvironmentType.PRODUCTION, EnvironmentStatus.ACTIVE, Instant.now(), Instant.now(), null));

        assertThatThrownBy(() -> service.enqueue(cmd(RunPriority.NORMAL)))
            .isInstanceOf(EnvironmentNotFoundException.class);

        verify(runQueueRepository, never()).enqueue(any(EnqueueRow.class));
    }

    @Test
    void enqueue_caseWithApiRequest_freezesApiRequestSnapshotInJson() {
        stubHappyPath(List.of(existingCaseWithApiRequest()));

        service.enqueue(cmd(RunPriority.NORMAL));

        var json = captureEnqueued().requestedEventJson();
        assertThat(json).contains("https://api.example.test/login");
        assertThat(json).contains("STATUS_EQUALS");
    }

    @Test
    void enqueue_caseWithSecretRefHeader_freezesOnlyKeyNeverPlaintext() {
        stubHappyPath(List.of(existingCaseWithSecretRefHeader()));

        service.enqueue(cmd(RunPriority.NORMAL));

        var json = captureEnqueued().requestedEventJson();
        assertThat(json).contains("API_TOKEN");
        assertThat(json).doesNotContain("s3cr3t-plaintext");
    }

    @Test
    void enqueue_fillStepWithSecretValue_freezesOnlyKey() {
        stubHappyPath(List.of(existingCaseWithSecretFillStep()));

        service.enqueue(cmd(RunPriority.NORMAL));

        var json = captureEnqueued().requestedEventJson();
        assertThat(json).contains("DEMO_PASSWORD");
    }

    // ---- fixtures ----

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
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Login works", "desc", 1,
            null, null, now, now, null);
    }

    private TestCase existingCaseWithApiRequest() {
        var now = Instant.now();
        var spec = new ApiRequestSpec("POST", "https://api.example.test/login",
            List.of(new ApiRequestSpec.HeaderPair("Accept", "application/json")),
            "{\"u\":1}", 200, 5000, 65536L,
            List.of(new ApiRequestSpec.ApiAssertionSpec("STATUS_EQUALS", "", "200")));
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Login works", "desc", 1,
            spec, null, now, now, null);
    }

    private TestCase existingCaseWithSecretRefHeader() {
        var now = Instant.now();
        var spec = new ApiRequestSpec("GET", "https://api.example.test/secure",
            List.of(new ApiRequestSpec.HeaderPair("Authorization", null, "API_TOKEN")),
            null, 200, 5000, 65536L,
            List.of(new ApiRequestSpec.ApiAssertionSpec("STATUS_EQUALS", "", "200")));
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Secure call", "desc", 1,
            spec, null, now, now, null);
    }

    private TestCase existingCaseWithSecretFillStep() {
        var now = Instant.now();
        var spec = new BrowserTestSpec("https://app.example.test/login",
            List.of(new BrowserTestSpec.BrowserStepSpec("NAVIGATE", null,
                        "https://app.example.test/login", null),
                    new BrowserTestSpec.BrowserStepSpec("FILL",
                        new BrowserTestSpec.SelectorSpec("LABEL", "Password", null, null),
                        null, null, "DEMO_PASSWORD")),
            List.of(new BrowserTestSpec.BrowserAssertionSpec("URL_CONTAINS", null, "/home")),
            60000, 15000, 30000);
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Login works", "desc", 1,
            null, spec, now, now, null);
    }
}
