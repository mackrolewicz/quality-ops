package com.qualityops.api.testsuite.domain;

import java.util.List;

/** Test-suite-owned declarative browser-test spec authored on a {@link TestCase}.
 *  Module-local, String-typed enums (mirrors {@link ApiRequestSpec}) — small
 *  duplication is acceptable to preserve module boundaries (ADR-003 §E). */
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
