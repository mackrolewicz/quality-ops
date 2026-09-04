package com.qualityops.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound to the same prefix as WorkerExecutionProperties but declares only the
 *  cancel-registry knob (ADR-006 §5.4), so adding it does not ripple every
 *  {@code TestProps.defaults(...)} call site. */
@ConfigurationProperties("qualityops.worker.execution")
public record CancellationProperties(Integer cancelRegistryMax) {

    public int effectiveMax() {
        return cancelRegistryMax != null && cancelRegistryMax > 0 ? cancelRegistryMax : 10_000;
    }
}
