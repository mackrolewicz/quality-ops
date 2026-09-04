package com.qualityops.api.execution.application.service;

import com.qualityops.api.execution.application.port.in.GetQueueAdminSummaryUseCase;
import com.qualityops.api.execution.application.port.in.GetRunConcurrencyUseCase;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import com.qualityops.api.execution.dto.QueueAdminSummary;
import com.qualityops.api.execution.dto.QueueAdminSummary.OrgQueue;
import com.qualityops.api.execution.dto.QueueAdminSummary.ProcessQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADR-007 §3. {@code org} aggregates from dedicated {@code …ForOrg} queries;
 *  {@code process} counters read straight off the {@link MeterRegistry}. */
@Service
@Transactional(readOnly = true)
public class QueueAdminService implements GetQueueAdminSummaryUseCase {

    private final RunQueueRepository runQueueRepository;
    private final GetRunConcurrencyUseCase getRunConcurrencyUseCase;
    private final MeterRegistry registry;

    public QueueAdminService(RunQueueRepository runQueueRepository,
                             GetRunConcurrencyUseCase getRunConcurrencyUseCase,
                             MeterRegistry registry) {
        this.runQueueRepository = runQueueRepository;
        this.getRunConcurrencyUseCase = getRunConcurrencyUseCase;
        this.registry = registry;
    }

    @Override
    public QueueAdminSummary summary(UUID orgId) {
        var depth = runQueueRepository.queueDepthByPriorityForOrg(orgId);
        Map<String, Long> byPriority = new LinkedHashMap<>();
        depth.forEach((k, v) -> byPriority.put(k.name(), v));

        Long oldest = runQueueRepository.oldestQueuedEnqueuedAtForOrg(orgId)
            .map(t -> Duration.between(t, Instant.now()).toSeconds())
            .orElse(null);
        long active = runQueueRepository.activeRunCountForOrg(orgId);

        var conc = getRunConcurrencyUseCase.get(orgId);
        var org = new OrgQueue(byPriority, oldest, active, conc.maxActiveRuns(), conc.source());

        var process = new ProcessQueue(
            counterSum("qualityops.queue.dispatch_throughput"),
            countersByTag("qualityops.queue.dispatch_failed", "reason"),
            countersByTag("qualityops.queue.reaped", "kind"),
            countersByTag("qualityops.queue.retries", "outcome"));
        return new QueueAdminSummary(org, process);
    }

    private double counterSum(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private Map<String, Double> countersByTag(String name, String tag) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Counter c : registry.find(name).counters()) {
            String value = c.getId().getTag(tag);
            if (value != null) {
                out.merge(value, c.count(), Double::sum);
            }
        }
        return out;
    }
}
