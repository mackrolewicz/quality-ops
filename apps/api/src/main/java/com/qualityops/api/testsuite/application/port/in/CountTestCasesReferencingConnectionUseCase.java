package com.qualityops.api.testsuite.application.port.in;

import java.util.UUID;

/** Read-only cross-module port (ADR-009 §11). Consumed by the {@code scm}
 *  module's connection-delete guard: counts non-deleted {@code test_cases} in
 *  the org whose {@code repo_test} spec references the given repository
 *  connection. */
public interface CountTestCasesReferencingConnectionUseCase {

    long countReferencingConnection(UUID connectionId, UUID orgId);
}
