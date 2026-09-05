package com.qualityops.worker.execution.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunFailedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.RunStartedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.AlreadyCompleted;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.AlreadyRunning;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.Claimed;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunLifecyclePublisher;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RunExecutionServiceTest {

    @Mock private RunLifecyclePublisher publisher;
    @Mock private ExecutionAttemptStore store;
    @Mock private ExecutionRunnerResolver resolver;
    @Mock private ExecutionRunner runner;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final com.qualityops.worker.execution.application.service.Sleeper noopSleeper = millis -> { };

    private RunExecutionService service;
    private com.qualityops.worker.execution.application.CancellationRegistry cancellationRegistry;

    private RunExecutionService newService(WorkerExecutionProperties p) {
        cancellationRegistry = new com.qualityops.worker.execution.application.CancellationRegistry(
            new com.qualityops.worker.config.CancellationProperties(null), p);
        return new RunExecutionService(publisher, store, resolver, p, mapper,
            new ArtifactUploadService(p, com.qualityops.worker.support.EmptyObjectProvider.instance(),
                com.qualityops.worker.support.EmptyObjectProvider.instance()),
            noopSleeper, cancellationRegistry, com.qualityops.worker.support.EmptyObjectProvider.instance());
    }

    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = newService(props(Mode.AUTO, Duration.ofMinutes(5)));
        lenient().when(resolver.resolvedKindFor(anyList())).thenReturn(RunnerKind.SIMULATED);
        lenient().when(resolver.resolve(any())).thenReturn(runner);
    }

    @Test
    void processRunRequested_claimed_publishesStartedThenCompleted_withCaseSummaries() {
        when(store.claim(eq(executionId), eq(runId), eq(orgId), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(eq(executionId), eq(0), eq("runs.completed"), any())).thenReturn(true);
        stubRunnerPassed();

        service.processRunRequested(event(2));

        InOrder ordered = inOrder(publisher);
        ordered.verify(publisher).publishRunStarted(any(RunStartedEvent.class));
        var captor = ArgumentCaptor.forClass(RunCompletedEvent.class);
        ordered.verify(publisher).publishRunCompleted(captor.capture());
        assertThat(captor.getValue().caseResults()).hasSize(2);
        assertThat(captor.getValue().caseResults())
            .allMatch(s -> s.verdict() == CaseResultSummary.Verdict.PASSED);
    }

    @Test
    void processRunRequested_alreadyCompleted_reemitsStoredTerminal_andDoesNotExecute() {
        when(store.claim(any(), any(), any(), any()))
            .thenReturn(new AlreadyCompleted("runs.completed", "{\"runId\":\"x\"}"));

        service.processRunRequested(event(1));

        verify(publisher).republishTerminal("runs.completed", runId, "{\"runId\":\"x\"}");
        verify(runner, never()).execute(any());
        verify(publisher, never()).publishRunStarted(any());
    }

    @Test
    void processRunRequested_alreadyRunningWithinLease_skipsSilently() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new AlreadyRunning(0, Instant.now()));
        when(store.steal(eq(executionId), any())).thenReturn(OptionalInt.empty());

        service.processRunRequested(event(1));

        verify(publisher, never()).publishRunStarted(any());
        verify(runner, never()).execute(any());
    }

    @Test
    void processRunRequested_alreadyRunningPastLease_stealsThenExecutes() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new AlreadyRunning(0, Instant.now()));
        when(store.steal(eq(executionId), any())).thenReturn(OptionalInt.of(1));
        when(store.markCompleted(eq(executionId), eq(1), any(), any())).thenReturn(true);
        stubRunnerPassed();

        service.processRunRequested(event(1));

        verify(publisher).publishRunStarted(any());
        verify(publisher).publishRunCompleted(any());
        verify(store).markCompleted(eq(executionId), eq(1), eq("runs.completed"), any());
    }

    @Test
    void processRunRequested_wallClockBudgetExceeded_remainingCasesErrored_completedFailed() {
        // 50 ms budget: case 0's check (first iteration) passes; its runner.execute
        // sleeps 200 ms past the budget, so case 1's check fails deterministically.
        service = newService(props(Mode.AUTO, Duration.ofMillis(50)));
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
        when(runner.execute(any())).thenAnswer(inv -> {
            Thread.sleep(200);
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return CaseExecutionResult.simulated(ctx.testCase().testCaseId(), ctx.testCase().name(),
                ctx.testCase().orderIndex(), CaseStatus.PASSED, Duration.ofMillis(200));
        });

        service.processRunRequested(event(2));

        var captor = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(publisher).publishRunCompleted(captor.capture());
        var completed = captor.getValue();
        assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(completed.caseResults().get(0).verdict()).isEqualTo(CaseResultSummary.Verdict.PASSED);
        assertThat(completed.caseResults().get(1).verdict()).isEqualTo(CaseResultSummary.Verdict.ERROR);
        assertThat(completed.caseResults().get(1).firstFailureReason())
            .isEqualTo("run wall-clock budget exceeded");
    }

    @Test
    void processRunRequested_harnessException_publishesRunFailed_withGenericReason() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), eq("runs.failed"), any())).thenReturn(true);
        when(runner.execute(any()))
            .thenThrow(new ExecutionHarnessException("target https://secret.example/x refused"));

        service.processRunRequested(event(1));

        var captor = ArgumentCaptor.forClass(RunFailedEvent.class);
        verify(publisher).publishRunFailed(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("Execution harness error");
        verify(publisher, never()).publishRunCompleted(any());
    }

    @Test
    void processRunRequested_markCompletedReturnsFalse_doesNotPublishCompleted() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(false);
        stubRunnerPassed();

        service.processRunRequested(event(1));

        verify(publisher, never()).publishRunCompleted(any());
    }

    @Test
    void processRunRequested_claimStoreThrows_propagates_andDoesNotPublishRunFailed() {
        when(store.claim(any(), any(), any(), any()))
            .thenThrow(new DataAccessResourceFailureException("worker db down"));

        assertThatThrownBy(() -> service.processRunRequested(event(1)))
            .isInstanceOf(DataAccessResourceFailureException.class);
        verify(publisher, never()).publishRunFailed(any());
    }

    @Test
    void processRunRequested_markCompletedThrowsAtTerminalWrite_propagates_andDoesNotPublish() {
        // ADR-003 §3.5: if the ledger is unreachable at the terminal write, rethrow
        // (→ Kafka retry → runs.requested.DLT), never emit a terminal event.
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any()))
            .thenThrow(new DataAccessResourceFailureException("worker db down at terminal write"));
        stubRunnerPassed();

        assertThatThrownBy(() -> service.processRunRequested(event(1)))
            .isInstanceOf(DataAccessResourceFailureException.class);
        verify(publisher).publishRunStarted(any());        // started was already emitted
        verify(publisher, never()).publishRunCompleted(any());
        verify(publisher, never()).publishRunFailed(any());
    }

    @Test
    void processRunRequested_v1EventNoApiRequest_routesAllCasesToSimulated() {
        when(resolver.resolvedKindFor(anyList())).thenReturn(RunnerKind.SIMULATED);
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
        stubRunnerPassed();

        service.processRunRequested(event(3));

        verify(store).claim(eq(executionId), eq(runId), eq(orgId), eq(RunnerKind.SIMULATED));
    }

    private void stubRunnerPassed() {
        lenient().when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            return CaseExecutionResult.simulated(ctx.testCase().testCaseId(), ctx.testCase().name(),
                ctx.testCase().orderIndex(), CaseStatus.PASSED, Duration.ofMillis(10));
        });
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

    private static WorkerExecutionProperties props(Mode mode, Duration budget) {
        return com.qualityops.worker.support.TestProps.defaults(mode, budget);
    }

    @Test
    void processRunRequested_browserCase_usesBrowserEffectiveTestTimeout() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
        var captured = new java.util.concurrent.atomic.AtomicReference<Duration>();
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            captured.set(ctx.effectiveTimeout());
            return CaseExecutionResult.simulated(ctx.testCase().testCaseId(), ctx.testCase().name(),
                ctx.testCase().orderIndex(), CaseStatus.PASSED, Duration.ofMillis(10));
        });

        service.processRunRequested(browserEvent(45000));

        assertThat(captured.get())
            .isEqualTo(com.qualityops.worker.support.TestProps.browser().effectiveTestTimeout(45000));
        assertThat(captured.get()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void processRunRequested_mixedRun_browserApiSimulated_aggregatesAndSummarisesUnchanged() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            var status = ctx.testCase().orderIndex() == 1 ? CaseStatus.FAILED : CaseStatus.PASSED;
            return CaseExecutionResult.simulated(ctx.testCase().testCaseId(), ctx.testCase().name(),
                ctx.testCase().orderIndex(), status, Duration.ofMillis(10));
        });

        service.processRunRequested(event(3));

        var captor = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(publisher).publishRunCompleted(captor.capture());
        var completed = captor.getValue();
        assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(completed.caseResults()).hasSize(3);
        assertThat(completed.caseResults().get(0).verdict()).isEqualTo(CaseResultSummary.Verdict.PASSED);
        assertThat(completed.caseResults().get(1).verdict()).isEqualTo(CaseResultSummary.Verdict.FAILED);
        assertThat(completed.caseResults().get(2).verdict()).isEqualTo(CaseResultSummary.Verdict.PASSED);
        verify(store).markCompleted(eq(executionId), eq(0), eq("runs.completed"), any());
    }

    @Test
    void processRunRequested_browserRunnerThrowsHarness_publishesRunFailedGenericReason() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), eq("runs.failed"), any())).thenReturn(true);
        when(runner.execute(any()))
            .thenThrow(new ExecutionHarnessException("browser execution interrupted",
                new InterruptedException()));

        service.processRunRequested(browserEvent(45000));

        var captor = ArgumentCaptor.forClass(RunFailedEvent.class);
        verify(publisher).publishRunFailed(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("Execution interrupted");
        verify(publisher, never()).publishRunCompleted(any());
    }

    private RunRequestedEvent browserEvent(int testTimeoutMillis) {
        var browser = new com.qualityops.events.BrowserTestSnapshot("https://app.example.test/",
            List.of(), List.of(), testTimeoutMillis, null, null);
        var cases = List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "browser-case", 0, null, browser));
        return new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), cases);
    }

    @Test
    void processRunRequested_cancelledBeforeStart_claimsThenPublishesRunFailed() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(eq(executionId), eq(0), eq("runs.failed"), any())).thenReturn(true);
        cancellationRegistry.markCancelled(executionId);

        service.processRunRequested(event(3));

        var captor = ArgumentCaptor.forClass(RunFailedEvent.class);
        verify(publisher).publishRunFailed(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("execution cancelled before start");
        verify(publisher, never()).publishRunStarted(any());
        verify(publisher, never()).publishRunCompleted(any());
        verify(runner, never()).execute(any());
    }

    @Test
    void processRunRequested_cancelledMidRun_remainingCasesErrored_runCompletesFailed() {
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            // flip the cancel signal once the first case has executed
            cancellationRegistry.markCancelled(executionId);
            return CaseExecutionResult.simulated(ctx.testCase().testCaseId(), ctx.testCase().name(),
                ctx.testCase().orderIndex(), CaseStatus.PASSED, Duration.ofMillis(5));
        });

        service.processRunRequested(event(4));

        var captor = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(publisher).publishRunCompleted(captor.capture());
        var completed = captor.getValue();
        assertThat(completed.outcome()).isEqualTo(RunOutcome.FAILED);
        var laterCases = completed.caseResults().stream()
            .filter(cr -> cr.verdict() == CaseResultSummary.Verdict.ERROR)
            .toList();
        assertThat(laterCases).isNotEmpty();
        assertThat(laterCases).allSatisfy(cr -> assertThat(cr.firstFailureReason()).isEqualTo("run cancelled"));
    }
}
