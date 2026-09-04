package com.qualityops.api.scheduling.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.scheduling.application.port.in.ScheduleUseCases;
import com.qualityops.api.scheduling.dto.CreateScheduleRequest;
import com.qualityops.api.scheduling.dto.NextFiresResponse;
import com.qualityops.api.scheduling.dto.ScheduleResponse;
import com.qualityops.api.scheduling.dto.UpdateScheduleRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Schedules", description = "Recurring / one-time run schedules (Phase 2C, ADR-006)")
public class ScheduleController {

    private static final String PRIORITY_GUARD =
        " and (#request.priority == null or #request.priority != 'HIGH' or hasAnyRole('OWNER','ADMIN'))";

    private final ScheduleUseCases schedules;

    public ScheduleController(ScheduleUseCases schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/api/v1/projects/{projectId}/schedules")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List schedules for a project")
    public ApiResponse<?> list(@PathVariable UUID projectId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = schedules.list(projectId, user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @PostMapping("/api/v1/projects/{projectId}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')" + PRIORITY_GUARD)
    @Operation(summary = "Create a schedule within a project")
    public ApiResponse<ScheduleResponse> create(@PathVariable UUID projectId,
                                                @Valid @RequestBody CreateScheduleRequest request,
                                                @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(
            schedules.create(projectId, request, user.orgId(), user.userId()));
    }

    @GetMapping("/api/v1/schedules/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Get a schedule by id")
    public ApiResponse<ScheduleResponse> get(@PathVariable UUID id,
                                             @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(schedules.get(id, user.orgId()));
    }

    @PutMapping("/api/v1/schedules/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')" + PRIORITY_GUARD)
    @Operation(summary = "Update a schedule (recomputes next_fire_at)")
    public ApiResponse<ScheduleResponse> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateScheduleRequest request,
                                                @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(schedules.update(id, request, user.orgId()));
    }

    @DeleteMapping("/api/v1/schedules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Delete a schedule")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        schedules.delete(id, user.orgId());
    }

    @PostMapping("/api/v1/schedules/{id}/pause")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Pause a schedule (enabled=false, next_fire_at=null)")
    public ApiResponse<ScheduleResponse> pause(@PathVariable UUID id,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(schedules.pause(id, user.orgId()));
    }

    @PostMapping("/api/v1/schedules/{id}/resume")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER')")
    @Operation(summary = "Resume a schedule (enabled=true, next_fire_at recomputed)")
    public ApiResponse<ScheduleResponse> resume(@PathVariable UUID id,
                                                @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(schedules.resume(id, user.orgId()));
    }

    @GetMapping("/api/v1/schedules/{id}/next-fires")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Preview the next N fire times (not stored)")
    public ApiResponse<NextFiresResponse> nextFires(@PathVariable UUID id,
                                                    @RequestParam(defaultValue = "5") int count,
                                                    @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(schedules.previewNextFires(id, user.orgId(), count));
    }
}
