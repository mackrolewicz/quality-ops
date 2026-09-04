package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.application.service.Sleeper;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Retains Phase-2A behaviour, now per case: sleep 20–80 ms, then an 80/20
 *  PASSED/FAILED draw. With a stubbed {@code random.nextInt(100)} every case
 *  draws the same value, so aggregate outcome is unchanged from 2A. */
@Component
public class SimulatedExecutionRunner implements ExecutionRunner {

    private static final int PASS_RATE_PERCENT = 80;
    private static final int MIN_SLEEP_MS = 20;
    private static final int SLEEP_SPREAD_MS = 60;   // 20..80

    private final RandomGenerator random;
    private final Sleeper sleeper;

    public SimulatedExecutionRunner(RandomGenerator random, Sleeper sleeper) {
        this.random = random;
        this.sleeper = sleeper;
    }

    @Override
    public RunnerKind kind() {
        return RunnerKind.SIMULATED;
    }

    @Override
    public CaseExecutionResult execute(CaseExecutionContext ctx) throws ExecutionHarnessException {
        long start = System.nanoTime();
        try {
            sleeper.sleep(MIN_SLEEP_MS + random.nextInt(SLEEP_SPREAD_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionHarnessException("simulated execution interrupted", e);
        }
        var status = random.nextInt(100) < PASS_RATE_PERCENT ? CaseStatus.PASSED : CaseStatus.FAILED;
        var c = ctx.testCase();
        // Simulated execution has no external side effect and never retries.
        return CaseExecutionResult.simulated(c.testCaseId(), c.name(), c.orderIndex(),
            status, Duration.ofNanos(System.nanoTime() - start))
            .withAttemptEpoch(ctx.attemptEpoch());
    }
}
