package com.qualityops.api.testsuite.application.port.in;

import java.util.UUID;

public interface DeleteTestCaseUseCase {
    void delete(UUID id, UUID orgId);
}
