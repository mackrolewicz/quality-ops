package com.qualityops.api.environment.application.service;

import com.qualityops.api.common.net.OutboundAddressGuard;
import com.qualityops.api.config.EnvironmentHealthProperties;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.environment.application.port.in.GetEnvironmentHealthUseCase;
import com.qualityops.api.environment.application.port.in.ProbeEnvironmentsUseCase;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthProbe.ProbeResult;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository.Candidate;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository.CurrentState;
import com.qualityops.api.environment.application.port.out.EnvironmentHealthRepository.RecordProbeCommand;
import com.qualityops.api.environment.domain.EnvironmentHealthStatus;
import com.qualityops.api.environment.dto.EnvironmentHealthResponse;
import com.qualityops.api.environment.exception.EnvironmentNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-008 §3 — environment-health probe orchestration. NOT class-{@code @Transactional}:
 * each environment is probed and persisted in its own {@link TransactionTemplate}
 * unit (mirrors {@code StuckRunReaperService}), so one failing probe never rolls
 * back another. The classification is a pure, unit-tested static function.
 */
@Service
public class EnvironmentHealthService implements GetEnvironmentHealthUseCase, ProbeEnvironmentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentHealthService.class);
    private static final String BLOCKED_DETAIL = "blocked:disallowed-target";
    private static final int ERROR_DETAIL_MAX = 500;

    private final EnvironmentHealthRepository repo;
    private final EnvironmentHealthProbe probe;
    private final OutboundAddressGuard guard;
    private final EnvironmentHealthProperties props;
    private final QueueMetrics metrics;
    private final TransactionTemplate tx;

    /** Refreshed at the end of every {@link #sweep()}; read by the per-status gauges. */
    private volatile Map<EnvironmentHealthStatus, Long> lastCounts = Map.of();

    public EnvironmentHealthService(EnvironmentHealthRepository repo,
                                   EnvironmentHealthProbe probe,
                                   OutboundAddressGuard guard,
                                   EnvironmentHealthProperties props,
                                   QueueMetrics metrics,
                                   MeterRegistry registry,
                                   PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.probe = probe;
        this.guard = guard;
        this.props = props;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
        for (EnvironmentHealthStatus status : EnvironmentHealthStatus.values()) {
            Gauge.builder("qualityops.environment.health", () -> lastCounts.getOrDefault(status, 0L))
                .tag("status", status.name())
                .register(registry);
        }
    }

    @Override
    public void sweep() {
        for (Candidate candidate : repo.selectDueBatch(props.batchSize(), props.probeInterval())) {
            try {
                probeAndRecord(candidate);
            } catch (RuntimeException e) {
                // Isolate one misbehaving environment — the rest of the batch still runs.
                log.warn("environment {} health probe failed unexpectedly", candidate.id(), e);
            }
        }
        this.lastCounts = repo.countActiveByHealthStatus();
    }

    private void probeAndRecord(Candidate candidate) {
        // Network I/O runs with NO transaction open — a slow probe must not pin a
        // pooled DB connection (up to probe-timeout, x batch-size, serially).
        boolean blocked = false;
        try {
            guard.check(candidate.baseUrl(), true, props.allowPrivateTargets());
        } catch (IllegalArgumentException e) {
            blocked = true;
            log.debug("environment {} health probe blocked: {}", candidate.id(), e.getMessage());
        }
        ProbeResult result = blocked ? null : probe.probe(candidate.baseUrl());

        // Only the read-then-write pair is transactional, and it is short.
        boolean wasBlocked = blocked;
        tx.executeWithoutResult(status -> persistProbe(candidate, wasBlocked, result));
    }

    private void persistProbe(Candidate candidate, boolean blocked, ProbeResult result) {
        CurrentState prev = repo.currentState(candidate.id(), candidate.orgId())
            .orElse(new CurrentState(EnvironmentHealthStatus.UNKNOWN, 0));

        if (blocked) {
            // consecutive_failures is left unchanged for a blocked target.
            repo.recordProbe(new RecordProbeCommand(candidate.id(), candidate.orgId(), candidate.projectId(),
                EnvironmentHealthStatus.UNKNOWN, Instant.now(), null, null, BLOCKED_DETAIL,
                prev.consecutiveFailures(), null));
            return;
        }

        Classification next = classify(prev.consecutiveFailures(), result,
            props.degradedAfter(), props.failureThreshold());
        Instant now = Instant.now();
        repo.recordProbe(new RecordProbeCommand(candidate.id(), candidate.orgId(), candidate.projectId(),
            next.status(), now, result.httpStatus(), result.latencyMs(), errorDetail(result),
            next.consecutiveFailures(), next.healthy() ? now : null));

        if (next.status() != prev.status()) {
            metrics.environmentHealthTransition(next.status().name());
        }
    }

    @Override
    public EnvironmentHealthResponse getHealth(UUID environmentId, UUID orgId) {
        var view = repo.getView(environmentId, orgId)
            .orElseThrow(() -> new EnvironmentNotFoundException("Environment not found: " + environmentId));
        return new EnvironmentHealthResponse(environmentId, view.healthStatus(), view.lastProbeAt(),
            view.lastHealthyAt(), view.consecutiveFailures(), view.recentChecks());
    }

    /**
     * Pure classification: {@code 2xx/3xx} ⇒ {@code HEALTHY} + failure count reset;
     * otherwise the failure count increments and yields {@code DEGRADED} once it
     * reaches {@code degradedAfter}, {@code DOWN} once it reaches {@code failureThreshold}.
     */
    static Classification classify(int prevFailures, ProbeResult result, int degradedAfter,
                                   int failureThreshold) {
        boolean ok = result.reachable() && result.httpStatus() != null
            && result.httpStatus() >= 200 && result.httpStatus() < 400;
        if (ok) {
            return new Classification(EnvironmentHealthStatus.HEALTHY, 0, true);
        }
        int failures = prevFailures + 1;
        if (failures >= failureThreshold) {
            return new Classification(EnvironmentHealthStatus.DOWN, failures, false);
        }
        if (failures >= degradedAfter) {
            return new Classification(EnvironmentHealthStatus.DEGRADED, failures, false);
        }
        return new Classification(EnvironmentHealthStatus.HEALTHY, 0, true);
    }

    private static String errorDetail(ProbeResult result) {
        if (result.error() == null) {
            return null;
        }
        return result.error().length() <= ERROR_DETAIL_MAX
            ? result.error()
            : result.error().substring(0, ERROR_DETAIL_MAX);
    }

    record Classification(EnvironmentHealthStatus status, int consecutiveFailures, boolean healthy) {}
}
