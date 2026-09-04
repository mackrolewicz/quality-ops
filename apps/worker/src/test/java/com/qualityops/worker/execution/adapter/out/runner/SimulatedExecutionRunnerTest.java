package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.CancellationToken;
import com.qualityops.worker.execution.application.service.Sleeper;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulatedExecutionRunnerTest {

    @Mock private RandomGenerator random;
    @Mock private Sleeper sleeper;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    // SimulatedExecutionRunner draws random.nextInt(SLEEP_SPREAD_MS==60) for sleep
    // jitter *before* random.nextInt(100) for the pass/fail verdict; both draws
    // must be stubbed or strict Mockito rejects the first as a mismatch.
    private static final int SLEEP_SPREAD = 60;

    @Test
    void execute_randomBelowPassRate_returnsPassed() throws Exception {
        when(random.nextInt(SLEEP_SPREAD)).thenReturn(0);
        when(random.nextInt(100)).thenReturn(10);

        var result = new SimulatedExecutionRunner(random, sleeper).execute(ctx());

        assertThat(result.status()).isEqualTo(CaseStatus.PASSED);
    }

    @Test
    void execute_randomAtOrAbovePassRate_returnsFailed() throws Exception {
        when(random.nextInt(SLEEP_SPREAD)).thenReturn(0);
        when(random.nextInt(100)).thenReturn(80);

        var result = new SimulatedExecutionRunner(random, sleeper).execute(ctx());

        assertThat(result.status()).isEqualTo(CaseStatus.FAILED);
    }

    @Test
    void execute_sleepInterrupted_throwsHarnessExceptionAndSetsInterruptFlag() throws Exception {
        doThrow(new InterruptedException()).when(sleeper).sleep(anyLong());
        var runner = new SimulatedExecutionRunner(random, sleeper);

        assertThatThrownBy(() -> runner.execute(ctx()))
            .isInstanceOf(ExecutionHarnessException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void execute_always_reportsDurationAndCaseIdentity() throws Exception {
        when(random.nextInt(SLEEP_SPREAD)).thenReturn(0);
        when(random.nextInt(100)).thenReturn(10);
        var item = new TestCaseSnapshotItem(UUID.randomUUID(), "the-case", 3);

        var result = new SimulatedExecutionRunner(random, sleeper).execute(ctx(item));

        assertThat(result.testCaseId()).isEqualTo(item.testCaseId());
        assertThat(result.name()).isEqualTo("the-case");
        assertThat(result.orderIndex()).isEqualTo(3);
        assertThat(result.duration()).isNotNull();
    }

    @Test
    void kind_isSimulated() {
        assertThat(new SimulatedExecutionRunner(random, sleeper).kind()).isEqualTo(RunnerKind.SIMULATED);
    }

    private CaseExecutionContext ctx() {
        return ctx(new TestCaseSnapshotItem(UUID.randomUUID(), "case", 0));
    }

    private CaseExecutionContext ctx(TestCaseSnapshotItem item) {
        return new CaseExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), item, Duration.ofSeconds(10), 1_048_576, CancellationToken.never());
    }
}
