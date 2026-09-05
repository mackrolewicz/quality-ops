package com.qualityops.api.execution.domain;

import java.util.List;

/** Domain mirror of the wire {@code ApiRequestSnapshot}, frozen into a run's
 *  config snapshot. Independent of the shared-events transport type. */
public record ApiRequestSpec(
        String method,
        String url,
        List<HeaderPair> headers,
        String body,
        Integer expectedStatus,
        Integer timeoutMillis,
        Long maxResponseBytes,
        List<ApiAssertionSpec> assertions
) {
    /** {@code secretRef} is the opaque credential key frozen into the snapshot
     *  when {@code value} is null; resolved by the Worker at execution time. */
    public record HeaderPair(String name, String value, String secretRef) {
        public HeaderPair(String name, String value) {
            this(name, value, null);
        }
    }
    public record ApiAssertionSpec(String type, String target, String expected) {}
}
