package com.qualityops.events;

import java.util.List;

/** Frozen declarative browser-test spec for one snapshot case. Nullable on
 *  {@link TestCaseSnapshotItem} — absent ⇒ the case is API or simulated.
 *  Mutually exclusive with {@link ApiRequestSnapshot}. */
public record BrowserTestSnapshot(
        String startUrl,
        List<BrowserStep> steps,
        List<BrowserAssertion> assertions,
        Integer testTimeoutMillis,        // nullable ⇒ worker default
        Integer stepTimeoutMillis,        // nullable ⇒ worker default
        Integer navigationTimeoutMillis   // nullable ⇒ worker default
) {}
