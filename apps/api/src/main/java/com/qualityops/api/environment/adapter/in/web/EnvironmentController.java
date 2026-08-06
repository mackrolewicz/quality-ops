package com.qualityops.api.environment.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.environment.application.port.in.CreateEnvironmentUseCase;
import com.qualityops.api.environment.application.port.in.DeleteEnvironmentUseCase;
import com.qualityops.api.environment.application.port.in.GetEnvironmentUseCase;
import com.qualityops.api.environment.application.port.in.ListEnvironmentsUseCase;
import com.qualityops.api.environment.application.port.in.UpdateEnvironmentUseCase;
import com.qualityops.api.environment.dto.CreateEnvironmentRequest;
import com.qualityops.api.environment.dto.EnvironmentResponse;
import com.qualityops.api.environment.dto.UpdateEnvironmentRequest;
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
@Tag(name = "Environments", description = "Environment registry within a project")
public class EnvironmentController {

    private final CreateEnvironmentUseCase createEnvironmentUseCase;
    private final ListEnvironmentsUseCase listEnvironmentsUseCase;
    private final GetEnvironmentUseCase getEnvironmentUseCase;
    private final UpdateEnvironmentUseCase updateEnvironmentUseCase;
    private final DeleteEnvironmentUseCase deleteEnvironmentUseCase;

    public EnvironmentController(CreateEnvironmentUseCase createEnvironmentUseCase,
                                 ListEnvironmentsUseCase listEnvironmentsUseCase,
                                 GetEnvironmentUseCase getEnvironmentUseCase,
                                 UpdateEnvironmentUseCase updateEnvironmentUseCase,
                                 DeleteEnvironmentUseCase deleteEnvironmentUseCase) {
        this.createEnvironmentUseCase = createEnvironmentUseCase;
        this.listEnvironmentsUseCase = listEnvironmentsUseCase;
        this.getEnvironmentUseCase = getEnvironmentUseCase;
        this.updateEnvironmentUseCase = updateEnvironmentUseCase;
        this.deleteEnvironmentUseCase = deleteEnvironmentUseCase;
    }

    @GetMapping("/api/v1/projects/{projectId}/environments")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List environments for a project")
    public ApiResponse<?> list(@PathVariable UUID projectId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listEnvironmentsUseCase.list(projectId, user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @PostMapping("/api/v1/projects/{projectId}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Create an environment within a project")
    public ApiResponse<EnvironmentResponse> create(@PathVariable UUID projectId,
                                                    @Valid @RequestBody CreateEnvironmentRequest request,
                                                    @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(
            createEnvironmentUseCase.create(projectId, request, user.orgId()));
    }

    @GetMapping("/api/v1/environments/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get an environment by id")
    public ApiResponse<EnvironmentResponse> get(@PathVariable UUID id,
                                                @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getEnvironmentUseCase.get(id, user.orgId()));
    }

    @PutMapping("/api/v1/environments/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Update an environment")
    public ApiResponse<EnvironmentResponse> update(@PathVariable UUID id,
                                                   @Valid @RequestBody UpdateEnvironmentRequest request,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(updateEnvironmentUseCase.update(id, request, user.orgId()));
    }

    @DeleteMapping("/api/v1/environments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Soft-delete an environment")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        deleteEnvironmentUseCase.delete(id, user.orgId());
    }
}
