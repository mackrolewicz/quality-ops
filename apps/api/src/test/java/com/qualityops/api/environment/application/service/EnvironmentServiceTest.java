package com.qualityops.api.environment.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.environment.application.port.out.EnvironmentRepository;
import com.qualityops.api.environment.domain.Environment;
import com.qualityops.api.environment.domain.EnvironmentStatus;
import com.qualityops.api.environment.domain.EnvironmentType;
import com.qualityops.api.environment.dto.CreateEnvironmentRequest;
import com.qualityops.api.environment.dto.UpdateEnvironmentRequest;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.domain.Project;
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
class EnvironmentServiceTest {

    @Mock
    private EnvironmentRepository environmentRepository;

    @Mock
    private GetProjectUseCase getProjectUseCase;

    private EnvironmentService environmentService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        environmentService = new EnvironmentService(environmentRepository, getProjectUseCase);
    }

    @Test
    void create_projectInDifferentOrgOrMissing_throwsProjectNotFoundException() {
        when(getProjectUseCase.getDomain(projectId, orgId))
            .thenThrow(new ProjectNotFoundException("Project not found: " + projectId));
        var request = new CreateEnvironmentRequest("Prod", "https://example.com", EnvironmentType.PRODUCTION);

        assertThatThrownBy(() -> environmentService.create(projectId, request, orgId))
            .isInstanceOf(ProjectNotFoundException.class);

        verify(environmentRepository, never()).save(any(Environment.class));
    }

    @Test
    void create_validProject_savesEnvironment() {
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new CreateEnvironmentRequest("Prod", "https://example.com", EnvironmentType.PRODUCTION);

        var response = environmentService.create(projectId, request, orgId);

        var captor = ArgumentCaptor.forClass(Environment.class);
        verify(environmentRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.orgId()).isEqualTo(orgId);
        assertThat(saved.projectId()).isEqualTo(projectId);
        assertThat(saved.name()).isEqualTo("Prod");
        assertThat(saved.baseUrl()).isEqualTo("https://example.com");
        assertThat(saved.type()).isEqualTo(EnvironmentType.PRODUCTION);
        assertThat(saved.status()).isEqualTo(EnvironmentStatus.ACTIVE);
        assertThat(response.name()).isEqualTo("Prod");
    }

    @Test
    void get_wrongOrgOrMissing_throwsEnvironmentNotFoundException() {
        when(environmentRepository.findByIdAndOrgId(environmentId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> environmentService.get(environmentId, orgId))
            .isInstanceOf(EnvironmentNotFoundException.class);
    }

    @Test
    void update_notFound_throwsEnvironmentNotFoundException() {
        when(environmentRepository.findByIdAndOrgId(environmentId, orgId)).thenReturn(Optional.empty());
        var request = new UpdateEnvironmentRequest("New name", "https://new.example.com",
            EnvironmentType.STAGING, EnvironmentStatus.INACTIVE);

        assertThatThrownBy(() -> environmentService.update(environmentId, request, orgId))
            .isInstanceOf(EnvironmentNotFoundException.class);
    }

    @Test
    void update_found_savesUpdatedFields() {
        var environment = existingEnvironment();
        when(environmentRepository.findByIdAndOrgId(environmentId, orgId)).thenReturn(Optional.of(environment));
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));
        var request = new UpdateEnvironmentRequest("New name", "https://new.example.com",
            EnvironmentType.STAGING, EnvironmentStatus.INACTIVE);

        var response = environmentService.update(environmentId, request, orgId);

        var captor = ArgumentCaptor.forClass(Environment.class);
        verify(environmentRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.name()).isEqualTo("New name");
        assertThat(saved.baseUrl()).isEqualTo("https://new.example.com");
        assertThat(saved.type()).isEqualTo(EnvironmentType.STAGING);
        assertThat(saved.status()).isEqualTo(EnvironmentStatus.INACTIVE);
        assertThat(saved.projectId()).isEqualTo(environment.projectId());
        assertThat(response.name()).isEqualTo("New name");
    }

    @Test
    void delete_notFound_throwsEnvironmentNotFoundException() {
        when(environmentRepository.findByIdAndOrgId(environmentId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> environmentService.delete(environmentId, orgId))
            .isInstanceOf(EnvironmentNotFoundException.class);

        verify(environmentRepository, never()).softDelete(any(), any(), any());
    }

    @Test
    void delete_found_callsSoftDelete() {
        var environment = existingEnvironment();
        when(environmentRepository.findByIdAndOrgId(environmentId, orgId)).thenReturn(Optional.of(environment));

        environmentService.delete(environmentId, orgId);

        verify(environmentRepository, times(1)).softDelete(eq(environmentId), eq(orgId), any(Instant.class));
    }

    @Test
    void list_validatesProjectThenDelegatesToRepository() {
        var environment = existingEnvironment();
        when(getProjectUseCase.getDomain(projectId, orgId)).thenReturn(existingProject());
        var repoPage = new PageResult<>(List.of(environment), 1, 20, 1L);
        when(environmentRepository.findAllByProjectIdAndOrgId(projectId, orgId, 1, 20)).thenReturn(repoPage);

        var result = environmentService.list(projectId, orgId, 1, 20);

        verify(getProjectUseCase).getDomain(projectId, orgId);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(environmentId);
    }

    private Project existingProject() {
        var now = Instant.now();
        return new Project(projectId, orgId, "Demo", "desc", "demo-slug", UUID.randomUUID(), now, now, null);
    }

    private Environment existingEnvironment() {
        var now = Instant.now();
        return new Environment(environmentId, orgId, projectId, "Prod", "https://example.com",
            EnvironmentType.PRODUCTION, EnvironmentStatus.ACTIVE, now, now, null);
    }
}
