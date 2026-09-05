package com.qualityops.api.environment.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.environment.application.port.in.GetEnvironmentHealthUseCase;
import com.qualityops.api.environment.dto.EnvironmentHealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADR-008 §3 — {@code GET /api/v1/environments/{id}/health}: current health + last 20 probes. */
@RestController
@Tag(name = "Environments")
public class EnvironmentHealthController {

    private final GetEnvironmentHealthUseCase useCase;

    public EnvironmentHealthController(GetEnvironmentHealthUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/api/v1/environments/{id}/health")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get environment health + recent probe history")
    public ApiResponse<EnvironmentHealthResponse> health(@PathVariable UUID id,
                                                         @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(useCase.getHealth(id, user.orgId()));
    }
}
