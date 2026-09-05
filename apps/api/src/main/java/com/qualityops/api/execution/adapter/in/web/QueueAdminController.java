package com.qualityops.api.execution.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.execution.application.port.in.GetQueueAdminSummaryUseCase;
import com.qualityops.api.execution.dto.QueueAdminSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADR-007 §3 — org queue summary + process-wide dispatch/reaper/retry counters. */
@RestController
@Tag(name = "Queue Admin", description = "Org queue summary + process dispatch/reaper/retry counters")
public class QueueAdminController {

    private final GetQueueAdminSummaryUseCase getQueueAdminSummaryUseCase;

    public QueueAdminController(GetQueueAdminSummaryUseCase getQueueAdminSummaryUseCase) {
        this.getQueueAdminSummaryUseCase = getQueueAdminSummaryUseCase;
    }

    @GetMapping("/api/v1/admin/queue")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Get this org's queue depth/wait/active + process-wide counters")
    public ApiResponse<QueueAdminSummary> queue(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getQueueAdminSummaryUseCase.summary(user.orgId()));
    }
}
