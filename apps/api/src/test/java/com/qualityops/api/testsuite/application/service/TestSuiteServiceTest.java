package com.qualityops.api.testsuite.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.domain.Project;
import com.qualityops.api.project.exception.ProjectNotFoundException;
import com.qualityops.api.testsuite.application.port.out.TestSuiteRepository;
import com.qualityops.api.testsuite.domain.SuiteType;
import com.qualityops.api.testsuite.domain.TestSuite;
import com.qualityops.api.testsuite.dto.CreateTestSuiteRequest;
import com.qualityops.api.testsuite.dto.UpdateTestSuiteRequest;
import com.qualityops.api.testsuite.exception.TestSuiteNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestSuiteServiceTest {

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private GetProjectUseCase getProjectUseCase;

    private TestSuiteService testSuiteService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testSuiteService = new TestSuiteService(testSuiteRepository, getProjectUseCase);
    }

    @Test
    void create_projectInDifferentOrgOrMissing_throwsProjectNotFoundException() {
        when(getProjectUseCase.getDomain(projectId, orgId))
            .thenThrow(new ProjectNotFoundException("Project not found: " + projectId));
        var request = new CreateTestSuiteRequest("Smoke", "desc", SuiteType.API);

        assertThatThrownBy(() -> testSuiteService.create(projectId, request, orgId))
            .isInstanceOf(ProjectNotFoundException.class);

        verify(testSuiteRepository, never()).save(any(TestSuite.class));
    }

    @Test
    void create_valid_savesSuite() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(testSuiteRepository.save(any(TestSuite.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateTestSuiteRequest("Smoke", "desc", SuiteType.API);

        var response = testSuiteService.create(projectId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestSuite.class);
        verify(testSuiteRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.orgId()).isEqualTo(orgId);
        assertThat(saved.projectId()).isEqualTo(projectId);
        assertThat(saved.name()).isEqualTo("Smoke");
        assertThat(saved.description()).isEqualTo("desc");
        assertThat(saved.type()).isEqualTo(SuiteType.API);
        assertThat(response.name()).isEqualTo("Smoke");
    }

    @Test
    void get_wrongOrgOrMissing_throwsTestSuiteNotFoundException() {
        when(testSuiteRepository.findByIdAndOrgId(suiteId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testSuiteService.get(suiteId, orgId))
            .isInstanceOf(TestSuiteNotFoundException.class);
    }

    @Test
    void update_notFound_throws() {
        when(testSuiteRepository.findByIdAndOrgId(suiteId, orgId)).thenReturn(Optional.empty());
        var request = new UpdateTestSuiteRequest("New name", "new desc", SuiteType.UI);

        assertThatThrownBy(() -> testSuiteService.update(suiteId, request, orgId))
            .isInstanceOf(TestSuiteNotFoundException.class);
    }

    @Test
    void update_found_savesUpdatedFields() {
        var suite = existingSuite();
        when(testSuiteRepository.findByIdAndOrgId(suiteId, orgId)).thenReturn(Optional.of(suite));
        when(testSuiteRepository.save(any(TestSuite.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new UpdateTestSuiteRequest("New name", "new desc", SuiteType.UI);

        var response = testSuiteService.update(suiteId, request, orgId);

        var captor = ArgumentCaptor.forClass(TestSuite.class);
        verify(testSuiteRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.name()).isEqualTo("New name");
        assertThat(saved.description()).isEqualTo("new desc");
        assertThat(saved.type()).isEqualTo(SuiteType.UI);
        assertThat(saved.projectId()).isEqualTo(suite.projectId());
        assertThat(response.name()).isEqualTo("New name");
    }

    @Test
    void delete_notFound_throws() {
        when(testSuiteRepository.findByIdAndOrgId(suiteId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> testSuiteService.delete(suiteId, orgId))
            .isInstanceOf(TestSuiteNotFoundException.class);

        verify(testSuiteRepository, never()).softDelete(any(), any(), any());
    }

    @Test
    void delete_found_callsSoftDelete() {
        var suite = existingSuite();
        when(testSuiteRepository.findByIdAndOrgId(suiteId, orgId)).thenReturn(Optional.of(suite));

        testSuiteService.delete(suiteId, orgId);

        verify(testSuiteRepository, times(1)).softDelete(eq(suiteId), eq(orgId), any(Instant.class));
    }

    @Test
    void list_validatesProjectThenDelegates() {
        var suite = existingSuite();
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        var repoPage = new PageResult<>(List.of(suite), 1, 20, 1L);
        when(testSuiteRepository.findAllByProjectIdAndOrgId(projectId, orgId, 1, 20)).thenReturn(repoPage);

        var result = testSuiteService.list(projectId, orgId, 1, 20);

        verify(getProjectUseCase).getDomain(projectId, orgId);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(suiteId);
    }

    private Project existingProject() {
        var now = Instant.now();
        return new Project(projectId, orgId, "Demo", "desc", "demo-slug", UUID.randomUUID(), now, now, null);
    }

    private TestSuite existingSuite() {
        var now = Instant.now();
        return new TestSuite(suiteId, orgId, projectId, "Smoke", "desc", SuiteType.API, now, now, null);
    }
}
