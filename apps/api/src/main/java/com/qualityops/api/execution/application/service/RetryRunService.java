package com.qualityops.api.execution.application.service;

import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunResult;
import com.qualityops.api.execution.application.port.in.RetryRunUseCase;
import com.qualityops.api.execution.application.port.out.RunQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** ADR-007 §2.3. Joins {@code RunLifecycleService}'s transaction ({@code REQUIRED})
 *  — the new test_runs + run_queue rows commit atomically with the terminal
 *  transition, and the {@code moved} boolean is the sole dedup point. */
@Service
@Transactional(propagation = Propagation.REQUIRED)
public class RetryRunService implements RetryRunUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetryRunService.class);

    private final RunQueueRepository runQueueRepository;
    private final EnqueueRunUseCase enqueueRunUseCase;
    private final SchedulingProperties props;
    private final QueueMetrics metrics;

    public RetryRunService(RunQueueRepository runQueueRepository,
                           EnqueueRunUseCase enqueueRunUseCase,
                           SchedulingProperties props,
                           QueueMetrics metrics) {
        this.runQueueRepository = runQueueRepository;
        this.enqueueRunUseCase = enqueueRunUseCase;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public Optional<EnqueueRunResult> retryIfEligible(UUID failedRunId, UUID orgId, String failureReason) {
        var r = props.retry();
        if (!r.enabled()) {
            return Optional.empty();
        }
        if (isNonRetryable(failureReason, r)) {
            metrics.retries("not_retryable");
            return Optional.empty();
        }
        var row = runQueueRepository.findByRunIdAndOrgId(failedRunId, orgId).orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        if (row.retryCount() >= r.maxPerRun()) {
            metrics.retries("budget_exhausted");
            return Optional.empty();
        }
        long recent = runQueueRepository.countRecentRetriesForOrg(orgId, Instant.now().minus(r.window()));
        if (recent >= r.maxActivePerOrg()) {
            metrics.retries("budget_exhausted");
            return Optional.empty();
        }
        var res = enqueueRunUseCase.enqueueRetry(failedRunId, orgId);
        metrics.retries("enqueued");
        log.info("run {} is retry #{} of {} (reason: {})",
            res.runId(), row.retryCount() + 1, failedRunId, failureReason);
        return Optional.of(res);
    }

    private static boolean isNonRetryable(String reason, SchedulingProperties.Retry r) {
        if (reason == null) {
            return false;
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        return r.nonRetryableReasonPrefixes().stream()
            .anyMatch(p -> lower.startsWith(p.toLowerCase(Locale.ROOT)));
    }
}
