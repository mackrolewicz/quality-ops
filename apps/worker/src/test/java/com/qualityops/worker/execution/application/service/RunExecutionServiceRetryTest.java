package com.qualityops.worker.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.Claimed;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunLifecyclePublisher;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunExecutionServiceRetryTest {

    @Mock private RunLifecyclePublisher publisher;
    @Mock private ExecutionAttemptStore store;
    @Mock private ExecutionRunnerResolver resolver;
    @Mock private ExecutionRunner runner;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Sleeper noopSleeper = millis -> { };

    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    private RunExecutionService service(WorkerExecutionProperties props) {
        return new RunExecutionService(publisher, store, resolver, props, mapper,
            new ArtifactUploadService(props, com.qualityops.worker.support.EmptyObjectProvider.instance(),
                com.qualityops.worker.support.EmptyObjectProvider.instance()),
            noopSleeper,
            new com.qualityops.worker.execution.application.CancellationRegistry(
                new com.qualityops.worker.config.CancellationProperties(null), props),
            com.qualityops.worker.support.EmptyObjectProvider.instance());
    }

    @BeforeEach
    void setUp() {
        lenient().when(resolver.resolvedKindFor(any())).thenReturn(RunnerKind.SIMULATED);
        lenient().when(resolver.resolve(any())).thenReturn(runner);
        lenient().when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        lenient().when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
    }

    @Test
    void transientTimeoutThenPassed_finalVerdictPassed_oneChunkAtEpochOne() {
        var attempts = new AtomicInteger();
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            int n = attempts.getAndIncrement();
            return result(ctx, n == 0 ? CaseStatus.TIMEOUT : CaseStatus.PASSED,
                SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });

        service(props(2)).processRunRequested(event(1));

        verify(runner, times(2)).execute(any());
        var chunk = ArgumentCaptor.forClass(ResultChunkEvent.class);
        verify(publisher).publishResultChunk(chunk.capture());
        assertThat(chunk.getValue().verdict()).isEqualTo(CaseResultSummary.Verdict.PASSED);
        assertThat(chunk.getValue().attemptEpoch()).isEqualTo(1);

        var completed = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(publisher).publishRunCompleted(completed.capture());
        var summary = completed.getValue().caseResults().get(0);
        assertThat(summary.verdict()).isEqualTo(CaseResultSummary.Verdict.PASSED);
        assertThat(summary.attemptEpoch()).isEqualTo(1);
    }

    @Test
    void errorWithPossibleSideEffect_notRetried() {
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return result(ctx, CaseStatus.ERROR, SideEffectClass.POSSIBLE, ctx.attemptEpoch());
        });

        service(props(2)).processRunRequested(event(1));

        verify(runner, times(1)).execute(any());
    }

    @Test
    void failedVerdict_notRetried() {
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return result(ctx, CaseStatus.FAILED, SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });

        service(props(2)).processRunRequested(event(1));

        verify(runner, times(1)).execute(any());
    }

    @Test
    void blockedVerdict_notRetried() {
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return result(ctx, CaseStatus.BLOCKED, SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });

        service(props(2)).processRunRequested(event(1));

        verify(runner, times(1)).execute(any());
    }

    @Test
    void maxAttemptsOne_disablesRetryEvenForRetryableTransient() {
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return result(ctx, CaseStatus.TIMEOUT, SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });

        service(props(1)).processRunRequested(event(1));

        verify(runner, times(1)).execute(any());
    }

    @Test
    void retryDisabled_neverRetries() {
        var props = TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), TestProps.artifacts(),
            TestProps.retry(false, 3), TestProps.secrets());
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return result(ctx, CaseStatus.TIMEOUT, SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });

        service(props).processRunRequested(event(1));

        verify(runner, times(1)).execute(any());
    }

    @Test
    void noBudgetRoomForAnotherAttempt_notRetried() {
        // Budget is tiny; the first attempt's execute() sleeps past it so no room remains.
        var props = TestProps.defaults(Mode.AUTO, Duration.ofMillis(30), TestProps.artifacts(),
            TestProps.retry(true, 3), TestProps.secrets());
        when(runner.execute(any())).thenAnswer(inv -> {
            Thread.sleep(60);
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return result(ctx, CaseStatus.TIMEOUT, SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });

        service(props).processRunRequested(event(1));

        verify(runner, times(1)).execute(any());
    }

    private static CaseExecutionResult result(CaseExecutionContext ctx, CaseStatus status,
                                              SideEffectClass sec, int epoch) {
        return new CaseExecutionResult(ctx.testCase().testCaseId(), ctx.testCase().name(),
            ctx.testCase().orderIndex(), status, Duration.ofMillis(5), null, null, List.of(),
            status == CaseStatus.PASSED ? null : status.name().toLowerCase(java.util.Locale.ROOT),
            null, sec, epoch);
    }

    private static WorkerExecutionProperties props(int maxAttempts) {
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), TestProps.artifacts(),
            TestProps.retry(true, maxAttempts), TestProps.secrets());
    }

    private RunRequestedEvent event(int caseCount) {
        var cases = new java.util.ArrayList<TestCaseSnapshotItem>();
        for (int i = 0; i < caseCount; i++) {
            cases.add(new TestCaseSnapshotItem(UUID.randomUUID(), "case-" + i, i));
        }
        return new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.copyOf(cases));
    }
}
