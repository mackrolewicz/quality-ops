package com.qualityops.api.execution.domain;

import java.util.List;

/** Domain mirror frozen into a run's config snapshot. Independent of the
 *  shared-events transport type. */
public record BrowserTestSpec(
        String startUrl,
        List<BrowserStepSpec> steps,
        List<BrowserAssertionSpec> assertions,
        Integer testTimeoutMillis,
        Integer stepTimeoutMillis,
        Integer navigationTimeoutMillis
) {
    public record SelectorSpec(String strategy, String value, String roleName, String accessibleName) {}
    /** {@code secretRef} is the opaque credential key for a FILL when {@code value} is null. */
    public record BrowserStepSpec(String action, SelectorSpec target, String value, String key, String secretRef) {
        public BrowserStepSpec(String action, SelectorSpec target, String value, String key) {
            this(action, target, value, key, null);
        }
    }
    public record BrowserAssertionSpec(String type, SelectorSpec target, String expected) {}
}
