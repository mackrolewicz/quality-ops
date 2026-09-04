package com.qualityops.api.config;

import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.domain.RunPriority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** ADR-006 §6 / ADR-007 §10. Query-backed gauges are refreshed into AtomicLongs
 *  every 10s so the scrape is O(1). No `org` tag anywhere (unbounded cardinality). */
@Component
public class QueueMetrics {

    private final MeterRegistry registry;
    private final RunQueueRepository runQueueRepository;

    private final AtomicLong depthHigh = new AtomicLong();
    private final AtomicLong depthNormal = new AtomicLong();
    private final AtomicLong depthLow = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong activeRuns = new AtomicLong();

    /** job name -> 1 while that ShedLock-locked job body is running, else 0. */
    private final Map<String, AtomicLong> leaderGauges = new ConcurrentHashMap<>();

    private final Timer waitTimer;
    private final Timer tickDuration;
    private final Timer dispatchDuration;
    private final Timer reaperDuration;
    private final Timer webhookDeliveryDuration;
    private final Timer probeDuration;
    private final Counter dispatchThroughput;

    public QueueMetrics(MeterRegistry registry, RunQueueRepository runQueueRepository) {
        this.registry = registry;
        this.runQueueRepository = runQueueRepository;

        Gauge.builder("qualityops.queue.depth", depthHigh, AtomicLong::get)
            .tag("priority", "HIGH").register(registry);
        Gauge.builder("qualityops.queue.depth", depthNormal, AtomicLong::get)
            .tag("priority", "NORMAL").register(registry);
        Gauge.builder("qualityops.queue.depth", depthLow, AtomicLong::get)
            .tag("priority", "LOW").register(registry);
        Gauge.builder("qualityops.queue.oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
            .register(registry);
        Gauge.builder("qualityops.queue.active_runs", activeRuns, AtomicLong::get)
            .register(registry);

        for (String job : List.of("scheduling-tick", "queue-dispatch", "stuck-run-reaper",
                "webhook-dispatch", "environment-health-probe")) {
            AtomicLong g = leaderGauges.computeIfAbsent(job, k -> new AtomicLong());
            Gauge.builder("qualityops.scheduling.leader", g, AtomicLong::get)
                .tag("job", job).register(registry);
        }

        this.waitTimer = Timer.builder("qualityops.queue.wait_seconds")
            .publishPercentileHistogram().register(registry);
        this.tickDuration = registry.timer("qualityops.scheduling.tick_duration");
        this.dispatchDuration = registry.timer("qualityops.queue.dispatch_duration");
        this.reaperDuration = registry.timer("qualityops.scheduling.reaper_duration");
        this.webhookDeliveryDuration = registry.timer("qualityops.webhook.delivery_duration");
        this.probeDuration = registry.timer("qualityops.environment.probe_duration");
        this.dispatchThroughput = registry.counter("qualityops.queue.dispatch_throughput");

        // Pre-register the tagged counters for every known outcome so a scrape (and a
        // `rate()`) sees them at 0 before the first event, not absent.
        for (String kind : List.of("redispatched", "redispatch_exhausted", "stuck_failed",
                "cancel_reconciled", "reaper_error")) {
            registry.counter("qualityops.queue.reaped", "kind", kind);
        }
        for (String outcome : List.of("enqueued", "budget_exhausted", "not_retryable")) {
            registry.counter("qualityops.queue.retries", "outcome", outcome);
        }
        for (String outcome : List.of("delivered", "failed", "exhausted")) {
            registry.counter("qualityops.webhook.delivery", "outcome", outcome);
        }
        for (String reason : List.of("attempts_ceiling", "corrupt_event")) {
            registry.counter("qualityops.queue.dispatch_failed", "reason", reason);
        }

        // ADR-008 — pre-register the bounded-domain 2E counters/timers so a scrape
        // sees them at 0 before the first event.
        for (String op : List.of("get", "put", "evict", "clear")) {
            registry.counter("qualityops.cache.errors", "op", op);
        }
        for (String op : List.of("run.trigger", "ci.run")) {
            registry.counter("qualityops.ratelimit.rejected", "operation", op);
        }
        registry.counter("qualityops.ratelimit.errors");
        for (String s : List.of("local", "redis")) {
            registry.counter("qualityops.ws.messages_sent", "scope", s);
        }
        for (String to : List.of("HEALTHY", "DEGRADED", "DOWN", "UNKNOWN")) {
            registry.counter("qualityops.environment.health_transitions", "to", to);
        }
        for (String o : List.of("SUCCESS", "FAILURE")) {
            registry.counter("qualityops.audit.written", "outcome", o);
        }
        for (String q : List.of("flaky", "trends", "slow")) {
            registry.timer("qualityops.analytics.query_duration", "query", q);
        }
    }

    /** Refreshes the query-backed gauges into their AtomicLongs. Triggered every
     *  10s by {@link QueueMetricsRefresher} (gated on jobs-enabled); no
     *  {@code @SchedulerLock} — each replica refreshes its own per-scrape state. */
    public void refresh() {
        var depth = runQueueRepository.queueDepthByPriority();
        depthHigh.set(depth.getOrDefault(RunPriority.HIGH, 0L));
        depthNormal.set(depth.getOrDefault(RunPriority.NORMAL, 0L));
        depthLow.set(depth.getOrDefault(RunPriority.LOW, 0L));
        oldestAgeSeconds.set(runQueueRepository.oldestQueuedEnqueuedAt()
            .map(t -> Duration.between(t, Instant.now()).toSeconds()).orElse(0L));
        activeRuns.set(runQueueRepository.activeRunCount());
    }

    public void recordDispatch(Instant enqueuedAt) {
        waitTimer.record(Duration.between(enqueuedAt, Instant.now()));
        dispatchThroughput.increment();
    }

    public void dispatchFailed(String reason) {
        registry.counter("qualityops.queue.dispatch_failed", "reason", reason).increment();
    }

    public void cancellation(String phase) {
        registry.counter("qualityops.queue.cancellations", "phase", phase).increment();
    }

    public void scheduleFire(String outcome) {
        registry.counter("qualityops.schedule.fires", "outcome", outcome).increment();
    }

    /** ADR-007 §1.4 — {@code kind ∈ {redispatched, redispatch_exhausted, stuck_failed,
     *  cancel_reconciled, reaper_error}}. */
    public void reaped(String kind) {
        registry.counter("qualityops.queue.reaped", "kind", kind).increment();
    }

    /** ADR-007 §2 — {@code outcome ∈ {enqueued, budget_exhausted, not_retryable}}. */
    public void retries(String outcome) {
        registry.counter("qualityops.queue.retries", "outcome", outcome).increment();
    }

    /** ADR-007 §6 — {@code outcome ∈ {delivered, failed, exhausted}}. */
    public void webhookDelivery(String outcome) {
        registry.counter("qualityops.webhook.delivery", "outcome", outcome).increment();
    }

    public void leaderHeld(String job, boolean held) {
        leaderGauges.computeIfAbsent(job, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("qualityops.scheduling.leader", g, AtomicLong::get)
                .tag("job", job).register(registry);
            return g;
        }).set(held ? 1 : 0);
    }

    public Timer tickDuration() {
        return tickDuration;
    }

    public Timer dispatchDuration() {
        return dispatchDuration;
    }

    public Timer reaperDuration() {
        return reaperDuration;
    }

    public Timer webhookDeliveryDuration() {
        return webhookDeliveryDuration;
    }

    // --- Phase 2E (ADR-008) meters ---

    public Timer probeDuration() {
        return probeDuration;
    }

    /** {@code query ∈ {flaky, trends, slow}}. */
    public Timer analyticsQuery(String query) {
        return registry.timer("qualityops.analytics.query_duration", "query", query);
    }

    /** {@code op ∈ {get, put, evict, clear}}. */
    public void cacheError(String op) {
        registry.counter("qualityops.cache.errors", "op", op).increment();
    }

    /** {@code operation ∈ {run.trigger, ci.run}}. */
    public void rateLimitRejected(String operation) {
        registry.counter("qualityops.ratelimit.rejected", "operation", operation).increment();
    }

    public void rateLimitError() {
        registry.counter("qualityops.ratelimit.errors").increment();
    }

    /** {@code scope ∈ {local, redis}}. */
    public void wsMessageSent(String scope) {
        registry.counter("qualityops.ws.messages_sent", "scope", scope).increment();
    }

    /** {@code to ∈ {HEALTHY, DEGRADED, DOWN, UNKNOWN}}. */
    public void environmentHealthTransition(String to) {
        registry.counter("qualityops.environment.health_transitions", "to", to).increment();
    }

    /** {@code outcome ∈ {SUCCESS, FAILURE}}. */
    public void auditWritten(String outcome) {
        registry.counter("qualityops.audit.written", "outcome", outcome).increment();
    }
}
