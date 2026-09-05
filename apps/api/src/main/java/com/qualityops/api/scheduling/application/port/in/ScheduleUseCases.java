package com.qualityops.api.scheduling.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.scheduling.dto.CreateScheduleRequest;
import com.qualityops.api.scheduling.dto.NextFiresResponse;
import com.qualityops.api.scheduling.dto.ScheduleResponse;
import com.qualityops.api.scheduling.dto.UpdateScheduleRequest;

import java.util.UUID;

/** Inbound port for the schedule aggregate CRUD + lifecycle (ADR-006 §1.6).
 *  Grouped in one interface because they form a single cohesive use case set and
 *  the module has exactly one implementation ({@code ScheduleService}). */
public interface ScheduleUseCases {

    ScheduleResponse create(UUID projectId, CreateScheduleRequest request, UUID orgId, UUID createdBy);

    ScheduleResponse update(UUID id, UpdateScheduleRequest request, UUID orgId);

    ScheduleResponse get(UUID id, UUID orgId);

    PageResult<ScheduleResponse> list(UUID projectId, UUID orgId, int page, int size);

    void delete(UUID id, UUID orgId);

    ScheduleResponse pause(UUID id, UUID orgId);

    ScheduleResponse resume(UUID id, UUID orgId);

    NextFiresResponse previewNextFires(UUID id, UUID orgId, int count);
}
