package com.qualityops.api.project.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.project.application.port.in.CreateProjectUseCase;
import com.qualityops.api.project.application.port.in.DeleteProjectUseCase;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.project.application.port.in.ListProjectsUseCase;
import com.qualityops.api.project.application.port.in.UpdateProjectUseCase;
import com.qualityops.api.project.dto.CreateProjectRequest;
import com.qualityops.api.project.dto.ProjectResponse;
import com.qualityops.api.project.dto.UpdateProjectRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Project CRUD within an organization")
public class ProjectController {

    private final CreateProjectUseCase createProjectUseCase;
    private final ListProjectsUseCase listProjectsUseCase;
    private final GetProjectUseCase getProjectUseCase;
    private final UpdateProjectUseCase updateProjectUseCase;
    private final DeleteProjectUseCase deleteProjectUseCase;

    public ProjectController(CreateProjectUseCase createProjectUseCase,
                             ListProjectsUseCase listProjectsUseCase,
                             GetProjectUseCase getProjectUseCase,
                             UpdateProjectUseCase updateProjectUseCase,
                             DeleteProjectUseCase deleteProjectUseCase) {
        this.createProjectUseCase = createProjectUseCase;
        this.listProjectsUseCase = listProjectsUseCase;
        this.getProjectUseCase = getProjectUseCase;
        this.updateProjectUseCase = updateProjectUseCase;
        this.deleteProjectUseCase = deleteProjectUseCase;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List projects in the caller's organization")
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listProjectsUseCase.list(user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Create a project in the caller's organization")
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(
            createProjectUseCase.create(request, user.orgId(), user.userId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get a project by id")
    public ApiResponse<ProjectResponse> get(@PathVariable UUID id,
                                            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getProjectUseCase.get(id, user.orgId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Update a project's name and description")
    public ApiResponse<ProjectResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateProjectRequest request,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(updateProjectUseCase.update(id, request, user.orgId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Soft-delete a project")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        deleteProjectUseCase.delete(id, user.orgId());
    }
}
