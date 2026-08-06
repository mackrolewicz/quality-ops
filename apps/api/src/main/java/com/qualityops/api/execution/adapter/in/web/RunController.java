package com.qualityops.api.execution.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.ListRunsUseCase;
import com.qualityops.api.execution.application.port.in.TriggerRunUseCase;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;
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
@Tag(name = "Test Runs", description = "Kafka-orchestrated test run triggering and status")
public class RunController {

    private final TriggerRunUseCase triggerRunUseCase;
    private final ListRunsUseCase listRunsUseCase;
    private final GetRunUseCase getRunUseCase;

    public RunController(TriggerRunUseCase triggerRunUseCase,
                          ListRunsUseCase listRunsUseCase,
                          GetRunUseCase getRunUseCase) {
        this.triggerRunUseCase = triggerRunUseCase;
        this.listRunsUseCase = listRunsUseCase;
        this.getRunUseCase = getRunUseCase;
    }

    @PostMapping("/api/v1/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Trigger a test run")
    public ApiResponse<RunResponse> trigger(@Valid @RequestBody CreateRunRequest request,
                                             @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(triggerRunUseCase.trigger(request, user.orgId(), user.userId()));
    }

    @GetMapping("/api/v1/runs")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List test runs, optionally filtered by project, suite, and status")
    public ApiResponse<?> list(@RequestParam(required = false) UUID projectId,
                               @RequestParam(required = false) UUID suiteId,
                               @RequestParam(required = false) RunStatus status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listRunsUseCase.list(user.orgId(), projectId, suiteId, status, page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @GetMapping("/api/v1/runs/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get a test run by id")
    public ApiResponse<RunResponse> get(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getRunUseCase.get(id, user.orgId()));
    }
}
