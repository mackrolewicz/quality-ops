package com.qualityops.api.result.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.result.application.port.in.GetDurationTrendsUseCase;
import com.qualityops.api.result.application.port.in.GetFlakyAnalyticsUseCase;
import com.qualityops.api.result.application.port.in.GetSlowTestsUseCase;
import com.qualityops.api.result.dto.DurationTrendsResponse;
import com.qualityops.api.result.dto.FlakyAnalyticsResponse;
import com.qualityops.api.result.dto.SlowTestsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only dashboard analytics (ADR-008 §1-2). {@code orgId} is always taken from the
 * JWT; {@code projectId} is required and org-checked in the service. Bounds are clamped
 * here so the Redis cache keys are canonical; the service re-clamps defensively.
 */
@RestController
@Tag(name = "Analytics", description = "Flaky detection, duration trends and slowest tests")
public class AnalyticsController {

    private static final int WINDOW_MIN = 5;
    private static final int WINDOW_MAX = 50;
    private static final int DAYS_MIN = 1;
    private static final int DAYS_MAX = 90;
    private static final int LIMIT_MIN = 1;
    private static final int LIMIT_MAX = 100;

    private final GetFlakyAnalyticsUseCase getFlakyAnalyticsUseCase;
    private final GetDurationTrendsUseCase getDurationTrendsUseCase;
    private final GetSlowTestsUseCase getSlowTestsUseCase;

    public AnalyticsController(GetFlakyAnalyticsUseCase getFlakyAnalyticsUseCase,
                              GetDurationTrendsUseCase getDurationTrendsUseCase,
                              GetSlowTestsUseCase getSlowTestsUseCase) {
        this.getFlakyAnalyticsUseCase = getFlakyAnalyticsUseCase;
        this.getDurationTrendsUseCase = getDurationTrendsUseCase;
        this.getSlowTestsUseCase = getSlowTestsUseCase;
    }

    @GetMapping("/api/v1/analytics/flaky")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Per-test flakiness / stability over the last N results")
    public ApiResponse<FlakyAnalyticsResponse> flaky(@RequestParam UUID projectId,
                                                     @RequestParam(defaultValue = "20") int window,
                                                     @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getFlakyAnalyticsUseCase.getFlaky(
            projectId, user.orgId(), clamp(window, WINDOW_MIN, WINDOW_MAX)));
    }

    @GetMapping("/api/v1/analytics/trends")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Daily run pass/fail counts and avg / p95 case duration")
    public ApiResponse<DurationTrendsResponse> trends(@RequestParam UUID projectId,
                                                      @RequestParam(defaultValue = "7") int days,
                                                      @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getDurationTrendsUseCase.getTrends(
            projectId, user.orgId(), clamp(days, DAYS_MIN, DAYS_MAX)));
    }

    @GetMapping("/api/v1/analytics/slow")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Top-N slowest tests by p95 duration")
    public ApiResponse<SlowTestsResponse> slow(@RequestParam UUID projectId,
                                               @RequestParam(defaultValue = "7") int days,
                                               @RequestParam(defaultValue = "20") int limit,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(getSlowTestsUseCase.getSlow(
            projectId, user.orgId(), clamp(days, DAYS_MIN, DAYS_MAX), clamp(limit, LIMIT_MIN, LIMIT_MAX)));
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.min(Math.max(value, lo), hi);
    }
}
