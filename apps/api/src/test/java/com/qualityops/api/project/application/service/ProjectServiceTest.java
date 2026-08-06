package com.qualityops.api.project.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.project.application.port.out.ProjectRepository;
import com.qualityops.api.project.domain.Project;
import com.qualityops.api.project.dto.CreateProjectRequest;
import com.qualityops.api.project.dto.UpdateProjectRequest;
import com.qualityops.api.project.exception.ProjectNotFoundException;
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
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    private ProjectService projectService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectRepository);
    }

    @Test
    void create_savesWithOrgIdAndCreatedBy() {
        var request = new CreateProjectRequest("Demo", "desc", "demo-slug");
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = projectService.create(request, orgId, userId);

        var captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.orgId()).isEqualTo(orgId);
        assertThat(saved.createdBy()).isEqualTo(userId);
        assertThat(saved.name()).isEqualTo("Demo");
        assertThat(saved.slug()).isEqualTo("demo-slug");
        assertThat(response.name()).isEqualTo("Demo");
    }

    @Test
    void get_found_returnsResponse() {
        var project = existingProject();
        when(projectRepository.findByIdAndOrgId(projectId, orgId)).thenReturn(Optional.of(project));

        var response = projectService.get(projectId, orgId);

        assertThat(response.id()).isEqualTo(projectId);
        assertThat(response.name()).isEqualTo("Demo");
    }

    @Test
    void get_wrongOrgOrMissing_throwsProjectNotFoundException() {
        when(projectRepository.findByIdAndOrgId(projectId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.get(projectId, orgId))
            .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void update_notFound_throwsProjectNotFoundException() {
        when(projectRepository.findByIdAndOrgId(projectId, orgId)).thenReturn(Optional.empty());
        var request = new UpdateProjectRequest("New name", "New desc");

        assertThatThrownBy(() -> projectService.update(projectId, request, orgId))
            .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void update_found_savesUpdatedFields() {
        var project = existingProject();
        when(projectRepository.findByIdAndOrgId(projectId, orgId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new UpdateProjectRequest("New name", "New desc");

        var response = projectService.update(projectId, request, orgId);

        var captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.name()).isEqualTo("New name");
        assertThat(saved.description()).isEqualTo("New desc");
        assertThat(saved.slug()).isEqualTo(project.slug());
        assertThat(response.name()).isEqualTo("New name");
    }

    @Test
    void delete_notFound_throwsProjectNotFoundException() {
        when(projectRepository.findByIdAndOrgId(projectId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete(projectId, orgId))
            .isInstanceOf(ProjectNotFoundException.class);

        verify(projectRepository, never()).softDelete(any(), any(), any());
    }

    @Test
    void delete_found_callsSoftDelete() {
        var project = existingProject();
        when(projectRepository.findByIdAndOrgId(projectId, orgId)).thenReturn(Optional.of(project));

        projectService.delete(projectId, orgId);

        verify(projectRepository, times(1)).softDelete(eq(projectId), eq(orgId), any(Instant.class));
    }

    @Test
    void list_delegatesToRepositoryAndReturnsPageResult() {
        var project = existingProject();
        var repoPage = new PageResult<>(List.of(project), 1, 20, 1L);
        when(projectRepository.findAllByOrgId(orgId, 1, 20)).thenReturn(repoPage);

        var result = projectService.list(orgId, 1, 20);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(projectId);
    }

    private Project existingProject() {
        var now = Instant.now();
        return new Project(projectId, orgId, "Demo", "desc", "demo-slug", userId, now, now, null);
    }
}
