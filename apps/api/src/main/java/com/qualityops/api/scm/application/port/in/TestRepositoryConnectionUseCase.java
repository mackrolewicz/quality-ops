package com.qualityops.api.scm.application.port.in;

import com.qualityops.api.scm.dto.TestConnectionResponse;

import java.util.UUID;

/** ADR-009 §11 — the outbound "test connection" probe action. */
public interface TestRepositoryConnectionUseCase {

    TestConnectionResponse test(UUID id, UUID orgId);
}
