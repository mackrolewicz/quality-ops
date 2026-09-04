package com.qualityops.api.testsuite.domain;

import java.util.List;

/** Test-suite-owned API-request spec authored on a {@link TestCase}. Kept
 *  module-local (mirrors {@code execution.domain.ApiRequestSpec}) to preserve
 *  module boundaries — small duplication is acceptable (ADR-003 §E). */
public record ApiRequestSpec(
        String method, String url, List<HeaderPair> headers, String body,
        Integer expectedStatus, Integer timeoutMillis, Long maxResponseBytes,
        List<ApiAssertionSpec> assertions
) {
    /** {@code secretRef} is the opaque credential key when {@code value} is null. */
    public record HeaderPair(String name, String value, String secretRef) {
        public HeaderPair(String name, String value) {
            this(name, value, null);
        }
    }
    public record ApiAssertionSpec(String type, String target, String expected) {}
}
