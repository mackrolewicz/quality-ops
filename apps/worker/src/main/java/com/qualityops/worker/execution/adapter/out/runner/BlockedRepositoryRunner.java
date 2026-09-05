package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * ADR-009 §1 — the rolling-deploy skew guard. Returned by
 * {@link com.qualityops.worker.execution.application.service.ExecutionRunnerResolver}
 * when a v5 {@code runs.requested} carries a {@code repoTest} case but this
 * Worker has no {@code REPOSITORY} runner registered (e.g. an old Worker, or
 * {@code qualityops.repo-exec.enabled=false}). Deterministically {@code BLOCKED}
 * — never an NPE, never a silent fall back to simulation.
 */
public class BlockedRepositoryRunner implements ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(BlockedRepositoryRunner.class);

    private final MeterRegistry registry;

    public BlockedRepositoryRunner(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RunnerKind kind() {
        return RunnerKind.REPOSITORY;
    }

    @Override
    public CaseExecutionResult execute(CaseExecutionContext ctx) {
        log.warn("Repository case {} received but no REPOSITORY runner is registered on this Worker "
            + "— blocking (rolling-deploy skew guard)", ctx.testCase().testCaseId());
        registry.counter("qualityops.repo.blocked", "reason", "worker_unavailable").increment();
        var c = ctx.testCase();
        return new CaseExecutionResult(c.testCaseId(), c.name(), c.orderIndex(), CaseStatus.BLOCKED,
            Duration.ZERO, null, null, List.of(), "repository execution unavailable on this worker", null,
            SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch(), null);
    }
}
