package com.qualityops.worker.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** ADR-009 §12 — the Worker-side {@code qualityops.repo.*} meters, pre-registered
 *  for every bounded-domain tag combination so a scrape (and a {@code rate()})
 *  sees them at 0 before the first repository run (mirrors the API's
 *  {@code QueueMetrics}). No {@code org} tag anywhere. */
@Component
@ConditionalOnProperty(name = "qualityops.repo-exec.enabled", havingValue = "true", matchIfMissing = true)
public class RepoExecMetrics {

    private static final List<String> PRESETS = List.of("PLAYWRIGHT", "JUNIT", "PYTEST", "CYPRESS", "K6");
    private static final List<String> PHASES = List.of("checkout", "framework");

    private final MeterRegistry registry;

    public RepoExecMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String preset : PRESETS) {
            for (String outcome : List.of("ok", "error")) {
                registry.timer("qualityops.repo.image_pull", "preset", preset, "outcome", outcome);
            }
            for (String phase : PHASES) {
                registry.timer("qualityops.repo.container_duration", "preset", preset, "phase", phase);
            }
            for (String outcome : List.of("passed", "failed", "timeout", "error", "blocked")) {
                registry.counter("qualityops.repo.runs", "preset", preset, "outcome", outcome);
            }
        }
        for (String format : List.of("JUNIT_XML", "K6_SUMMARY_JSON")) {
            for (String outcome : List.of("ok", "error")) {
                registry.timer("qualityops.repo.report_parse", "format", format, "outcome", outcome);
            }
        }
        for (String status : List.of("passed", "failed", "skipped", "error")) {
            registry.counter("qualityops.repo.items", "status", status);
        }
        for (String reason : List.of("host_denied", "image_not_allowlisted", "digest_mismatch",
                "secret_unresolved", "spec_invalid", "worker_unavailable")) {
            registry.counter("qualityops.repo.blocked", "reason", reason);
        }
        for (String reason : List.of("timeout", "cancel", "workspace_quota", "sweep")) {
            registry.counter("qualityops.repo.container_kills", "reason", reason);
        }
        registry.counter("qualityops.repo.orphans_swept");
    }

    public Timer imagePull(String preset, String outcome) {
        return registry.timer("qualityops.repo.image_pull", "preset", preset, "outcome", outcome);
    }

    public Timer containerDuration(String preset, String phase) {
        return registry.timer("qualityops.repo.container_duration", "preset", preset, "phase", phase);
    }

    public void run(String preset, String outcome) {
        registry.counter("qualityops.repo.runs", "preset", preset, "outcome", outcome).increment();
    }

    public void reportParse(String format, String outcome, Duration elapsed) {
        registry.timer("qualityops.repo.report_parse", "format", format, "outcome", outcome).record(elapsed);
    }

    public void item(String status) {
        registry.counter("qualityops.repo.items", "status", status).increment();
    }

    /** {@code reason ∈ {host_denied, image_not_allowlisted, digest_mismatch,
     *  secret_unresolved, spec_invalid, worker_unavailable}}. */
    public void blocked(String reason) {
        registry.counter("qualityops.repo.blocked", "reason", reason).increment();
    }

    /** {@code reason ∈ {timeout, cancel, workspace_quota, sweep}}. */
    public void containerKill(String reason) {
        registry.counter("qualityops.repo.container_kills", "reason", reason).increment();
    }

    public Counter orphansSwept() {
        return registry.counter("qualityops.repo.orphans_swept");
    }
}
