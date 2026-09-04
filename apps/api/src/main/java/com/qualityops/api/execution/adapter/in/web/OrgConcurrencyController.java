package com.qualityops.api.execution.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.execution.application.port.in.GetRunConcurrencyUseCase;
import com.qualityops.api.execution.application.port.in.SetRunConcurrencyUseCase;
import com.qualityops.api.execution.dto.RunConcurrencyResponse;
import com.qualityops.api.execution.dto.SetRunConcurrencyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADR-007 §4 — an org admin sets their own per-org concurrency cap. Cross-org
 *  administration is Phase 4 (no platform-admin role in 2D). */
@RestController
@Tag(name = "Org Run Concurrency", description = "Per-org max concurrent active runs (ADR-007 §4)")
public class OrgConcurrencyController {

    private final SetRunConcurrencyUseCase setRunConcurrencyUseCase;
    private final GetRunConcurrencyUseCase getRunConcurrencyUseCase;

    public OrgConcurrencyController(SetRunConcurrencyUseCase setRunConcurrencyUseCase,
                                   GetRunConcurrencyUseCase getRunConcurrencyUseCase) {
        this.setRunConcurrencyUseCase = setRunConcurrencyUseCase;
        this.getRunConcurrencyUseCase = getRunConcurrencyUseCase;
    }

    @PutMapping("/api/v1/admin/orgs/{orgId}/run-concurrency")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Set this org's max concurrent active runs")
    public ApiResponse<RunConcurrencyResponse> set(@PathVariable UUID orgId,
            @Valid @RequestBody SetRunConcurrencyRequest body,
            @AuthenticationPrincipal UserPrincipal user) {
        requireOwnOrg(orgId, user);
        return ApiResponse.success(
            setRunConcurrencyUseCase.set(orgId, body.maxActiveRuns(), user.userId()));
    }

    @GetMapping("/api/v1/admin/orgs/{orgId}/run-concurrency")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Get this org's effective max concurrent active runs + source")
    public ApiResponse<RunConcurrencyResponse> get(@PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal user) {
        requireOwnOrg(orgId, user);
        return ApiResponse.success(getRunConcurrencyUseCase.get(orgId));
    }

    private static void requireOwnOrg(UUID orgId, UserPrincipal user) {
        if (!orgId.equals(user.orgId())) {
            throw new AccessDeniedException("cross-org run-concurrency administration is not permitted");
        }
    }
}
