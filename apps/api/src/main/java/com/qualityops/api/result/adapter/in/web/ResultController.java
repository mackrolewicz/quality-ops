package com.qualityops.api.result.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.result.application.port.in.GetAnalyticsUseCase;
import com.qualityops.api.result.application.port.in.ListResultsUseCase;
import com.qualityops.api.result.dto.AnalyticsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Test Results", description = "Per-run test results and project analytics")
public class ResultController {

    private final ListResultsUseCase listResultsUseCase;
    private final GetAnalyticsUseCase getAnalyticsUseCase;

    public ResultController(ListResultsUseCase listResultsUseCase, GetAnalyticsUseCase getAnalyticsUseCase) {
        this.listResultsUseCase = listResultsUseCase;
        this.getAnalyticsUseCase = getAnalyticsUseCase;
    }

    @GetMapping("/api/v1/runs/{runId}/results")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List test results for a run")
    public ApiResponse<?> list(@PathVariable UUID runId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listResultsUseCase.list(runId, user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @GetMapping("/api/v1/projects/{projectId}/analytics")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get run pass/fail analytics for a project over a recent window")
    public ApiResponse<AnalyticsResponse> analytics(@PathVariable UUID projectId,
                                                     @RequestParam(defaultValue = "7") int days,
                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getAnalyticsUseCase.getAnalytics(projectId, user.orgId(), days));
    }
}
