package com.qualityops.api.execution.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.common.ratelimit.RateLimited;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.execution.application.port.in.SubmitCiRunUseCase;
import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** ADR-007 §5 — idempotent CI run submission. 200 on the first call AND every
 *  subsequent same-key+same-body call; 409 {@code IDEMPOTENCY_KEY_CONFLICT} on a
 *  same-key different-body call. */
@RestController
@Validated
@Tag(name = "CI Execution", description = "Idempotent run submission for CI pipelines")
public class CiRunController {

    private final SubmitCiRunUseCase submitCiRunUseCase;

    public CiRunController(SubmitCiRunUseCase submitCiRunUseCase) {
        this.submitCiRunUseCase = submitCiRunUseCase;
    }

    @PostMapping("/api/v1/ci/runs")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER') and "
        + "(#request.priority == null or #request.priority != 'HIGH' or hasAnyRole('OWNER','ADMIN'))")
    @RateLimited(operation = "ci.run",
        limit = "${qualityops.ratelimit.ci-run.limit:120}",
        window = "${qualityops.ratelimit.ci-run.window:PT1H}")
    @Operation(summary = "Enqueue a run from CI, idempotent per Idempotency-Key")
    public ResponseEntity<ApiResponse<RunResponse>> submit(
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9_.\\-]{1,200}") String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.success(
            submitCiRunUseCase.submit(idempotencyKey, request, user.orgId(), user.userId())));
    }
}
