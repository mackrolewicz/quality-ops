package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.testsuite.application.port.in.GetTestSuiteUseCase;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestCase;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.dto.CreateTestCaseRequest;
import com.qualityops.api.testsuite.dto.RepoTestPayload;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCaseServiceRepoTest {

    @Mock private TestCaseRepository testCaseRepository;
    @Mock private GetTestSuiteUseCase getTestSuiteUseCase;

    private TestCaseService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TestCaseService(testCaseRepository, getTestSuiteUseCase);
    }

    private TestSuite suite() {
        var now = Instant.now();
        return new TestSuite(suiteId, orgId, UUID.randomUUID(), "Smoke", "desc", SuiteType.API, now, now, null);
    }

    private RepoTestPayload repoPayload() {
        return new RepoTestPayload(connectionId, "main", "PYTEST", "svc",
            List.of("pytest", "--junitxml=report.xml"), "JUNIT_XML",
            List.of("report.xml"), List.of("screenshots/**"),
            List.of(new RepoTestPayload.EnvVarPayload("CI_NAME", "qualityops")),
            List.of(new RepoTestPayload.SecretVarPayload("REGISTRY_TOKEN", "REGISTRY_PAT")),
            "MEDIUM", "EGRESS", 900);
    }

    @Test
    void create_withRepoTestPayload_mapsEveryFieldIntoDomainSpec() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(suite());
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestCaseRequest("Repo case", "desc", 0, null, null, repoPayload());

        service.create(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        var spec = captor.getValue().repoTest();
        assertThat(spec).isNotNull();
        assertThat(spec.repositoryConnectionId()).isEqualTo(connectionId);
        assertThat(spec.requestedRef()).isEqualTo("main");
        assertThat(spec.framework()).isEqualTo("PYTEST");
        assertThat(spec.workingDir()).isEqualTo("svc");
        assertThat(spec.command()).containsExactly("pytest", "--junitxml=report.xml");
        assertThat(spec.reportFormat()).isEqualTo("JUNIT_XML");
        assertThat(spec.reportPaths()).containsExactly("report.xml");
        assertThat(spec.artifactGlobs()).containsExactly("screenshots/**");
        assertThat(spec.environmentVars()).singleElement()
            .satisfies(e -> assertThat(e.name()).isEqualTo("CI_NAME"));
        assertThat(spec.secretVars()).singleElement()
            .satisfies(s -> assertThat(s.secretRef()).isEqualTo("REGISTRY_PAT"));
        assertThat(spec.resourceProfile()).isEqualTo("MEDIUM");
        assertThat(spec.networkPolicy()).isEqualTo("EGRESS");
        assertThat(spec.timeoutSeconds()).isEqualTo(900);
    }

    @Test
    void create_withRepoTestAndBrowserTest_isRejected() {
        when(getTestSuiteUseCase.getDomain(suiteId, orgId)).thenReturn(suite());
        var browser = new com.qualityops.api.testsuite.dto.BrowserTestPayload(
            "https://app.example.test", List.of(
                new com.qualityops.api.testsuite.dto.BrowserTestPayload.BrowserStepPayload(
                    "NAVIGATE", null, "https://app.example.test", null)),
            List.of(new com.qualityops.api.testsuite.dto.BrowserTestPayload.BrowserAssertionPayload(
                "URL_CONTAINS", null, "/")), null, null, null);
        var request = new CreateTestCaseRequest("Mixed", "desc", 0, null, browser, repoPayload());

        assertThatThrownBy(() -> service.create(suiteId, request, orgId))
            .isInstanceOf(IllegalArgumentException.class);
        verify(testCaseRepository, never()).save(any(TestCase.class));
    }

    @Test
    void countReferencingConnection_delegatesToRepositoryWithOrgAndConnection() {
        when(testCaseRepository.countReferencingConnection(orgId, connectionId)).thenReturn(3L);

        assertThat(service.countReferencingConnection(connectionId, orgId)).isEqualTo(3L);
    }
}
