package com.qualityops.worker.execution.application;

import com.qualityops.worker.config.CancellationProperties;
import com.qualityops.worker.config.WorkerExecutionProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, in-memory cancel signal keyed by executionId. Fed by RunCancelConsumer.
 *  No persistence, no {@code worker} schema change (ADR-006 §5.4): a redelivered or
 *  lease-stolen execution restarts every case anyway, and the only HARD cancel
 *  guarantee (QUEUED -> CANCELLED) never involves the Worker. */
@Component
public class CancellationRegistry {

    private final Map<UUID, Instant> cancelled = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final Duration ttl;

    public CancellationRegistry(CancellationProperties cancelProps, WorkerExecutionProperties execProps) {
        this.maxEntries = cancelProps.effectiveMax();
        this.ttl = execProps.runWallClockBudget().multipliedBy(2); // older entries are useless
    }

    public void markCancelled(UUID executionId) {
        sweep();
        if (cancelled.size() >= maxEntries) {
            evictOldest();
        }
        cancelled.put(executionId, Instant.now());
    }

    public boolean isCancelled(UUID executionId) {
        return cancelled.containsKey(executionId);
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(ttl);
        cancelled.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    private void evictOldest() {
        cancelled.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .ifPresent(cancelled::remove);
    }
}
