package com.qualityops.api.scm.application.service;

import com.qualityops.events.RepositoryProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** ADR-009 §12 — the API-side {@code qualityops.repo.ref_resolve} timer,
 *  pre-registered for every {@code provider}/{@code outcome} pair so a scrape
 *  sees it at 0 before the first resolution (mirrors {@code QueueMetrics}). No
 *  {@code org} tag (bounded cardinality). */
@Component
public class ScmMetrics {

    static final List<String> OUTCOMES =
        List.of("resolved", "not_found", "auth_failed", "host_denied", "error");

    private final MeterRegistry registry;

    public ScmMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (RepositoryProvider provider : RepositoryProvider.values()) {
            for (String outcome : OUTCOMES) {
                registry.timer("qualityops.repo.ref_resolve", "provider", provider.name(), "outcome", outcome);
            }
        }
    }

    /** {@code outcome ∈ {resolved, not_found, auth_failed, host_denied, error}}. */
    public void refResolve(RepositoryProvider provider, String outcome, Duration elapsed) {
        registry.timer("qualityops.repo.ref_resolve", "provider", provider.name(), "outcome", outcome)
            .record(elapsed);
    }
}
