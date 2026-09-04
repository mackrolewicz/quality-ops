package com.qualityops.api.execution.application.port.in;

import com.qualityops.api.execution.dto.CreateRunRequest;
import com.qualityops.api.execution.dto.RunResponse;

import java.util.UUID;

/** ADR-007 §5 — idempotent CI run submission. Returns the SAME run + HTTP 200 on
 *  the first call and every subsequent same-key+same-body call; a same-key
 *  different-body call throws {@code IdempotencyKeyConflictException} (409). */
public interface SubmitCiRunUseCase {

    RunResponse submit(String idempotencyKey, CreateRunRequest body, UUID orgId, UUID userId);
}
