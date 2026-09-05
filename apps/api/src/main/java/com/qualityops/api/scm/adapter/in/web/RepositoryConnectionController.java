package com.qualityops.api.scm.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.common.ratelimit.RateLimited;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.scm.application.port.in.ManageRepositoryConnectionsUseCase;
import com.qualityops.api.scm.application.port.in.TestRepositoryConnectionUseCase;
import com.qualityops.api.scm.dto.RegisterRepositoryConnectionRequest;
import com.qualityops.api.scm.dto.RepositoryConnectionResponse;
import com.qualityops.api.scm.dto.TestConnectionResponse;
import com.qualityops.api.scm.dto.UpdateRepositoryConnectionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADR-009 §11 — repository-connection CRUD + the outbound "test connection"
 *  action. Repo test <em>specs</em> are authored through the existing
 *  {@code POST/PUT .../cases} endpoints; repo runs go through the existing
 *  {@code POST /api/v1/runs} / {@code /ci/runs} / schedule flows. */
@RestController
@Tag(name = "Repository connections", description = "GitHub/GitLab repository connections (ADR-009 §11)")
public class RepositoryConnectionController {

    private final ManageRepositoryConnectionsUseCase connections;
    private final TestRepositoryConnectionUseCase tester;

    public RepositoryConnectionController(ManageRepositoryConnectionsUseCase connections,
                                         TestRepositoryConnectionUseCase tester) {
        this.connections = connections;
        this.tester = tester;
    }

    @PostMapping("/api/v1/projects/{projectId}/repository-connections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Register a repository connection for a project")
    public ApiResponse<RepositoryConnectionResponse> register(@PathVariable UUID projectId,
            @Valid @RequestBody RegisterRepositoryConnectionRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(connections.register(projectId, user.orgId(), request, user.userId()));
    }

    @GetMapping("/api/v1/projects/{projectId}/repository-connections")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List a project's repository connections")
    public ApiResponse<List<RepositoryConnectionResponse>> list(@PathVariable UUID projectId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(connections.list(projectId, user.orgId()));
    }

    @GetMapping("/api/v1/repository-connections/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get a repository connection by id")
    public ApiResponse<RepositoryConnectionResponse> get(@PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(connections.get(id, user.orgId()));
    }

    @PutMapping("/api/v1/repository-connections/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Update a repository connection")
    public ApiResponse<RepositoryConnectionResponse> update(@PathVariable UUID id,
            @Valid @RequestBody UpdateRepositoryConnectionRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(connections.update(id, user.orgId(), request));
    }

    @DeleteMapping("/api/v1/repository-connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Soft-delete a repository connection (409 if referenced by a test case)")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        connections.delete(id, user.orgId());
    }

    @PostMapping("/api/v1/repository-connections/{id}/test")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @RateLimited(operation = "scm.test-connection",
        limit = "${qualityops.ratelimit.scm-test.limit:30}", window = "PT1H")
    @Operation(summary = "Test a repository connection (outbound probe)")
    public ApiResponse<TestConnectionResponse> test(@PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(tester.test(id, user.orgId()));
    }
}
