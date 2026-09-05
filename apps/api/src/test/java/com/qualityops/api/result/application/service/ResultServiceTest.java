package com.qualityops.api.result.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.exception.RunNotFoundException;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.application.port.out.RepositoryTestItemRepository;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.result.domain.ResultStatus;
import com.qualityops.api.result.domain.TestResult;
import com.qualityops.api.result.domain.TestResultArtifact;
import com.qualityops.events.ArtifactReference;
import com.qualityops.events.ArtifactType;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.TestCaseSnapshotItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultServiceTest {

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private ArtifactMetadataRepository artifactMetadataRepository;

    @Mock
    private GetRunStatsUseCase getRunStatsUseCase;

    @Mock
    private GetRunUseCase getRunUseCase;

    @Mock
    private RunProgressNotifier runProgressNotifier;

    @Mock
    private RepositoryTestItemRepository repositoryTestItemRepository;

    @Mock
    private RepositoryRunWriteUseCase repositoryRunWriteUseCase;

    @Mock
    private RepoExecApiProperties repoExecApiProperties;

    private ResultService resultService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resultService = new ResultService(testResultRepository, artifactMetadataRepository,
            repositoryTestItemRepository, repositoryRunWriteUseCase, getRunStatsUseCase, getRunUseCase,
            runProgressNotifier, repoExecApiProperties);
    }

    @Test
    void generateResults_foreignTenantRun_isNoOpAndNeverUpserts() {
        when(getRunUseCase.getDomain(runId, orgId))
            .thenThrow(new RunNotFoundException("Run not found: " + runId));
        var event = runCompletedEvent(RunOutcome.PASSED, List.of(existingCase()));

        resultService.generateResults(event);

        verify(testResultRepository, never()).upsert(any());
        verify(artifactMetadataRepository, never()).upsertForCase(any(), any(), any(), anyInt(), anyList());
    }

    @Test
    void generateResults_staleExecutionId_skips() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var event = runCompletedEvent(RunOutcome.PASSED, List.of(existingCase()), UUID.randomUUID());

        resultService.generateResults(event);

        verify(testResultRepository, never()).upsert(any());
    }

    @Test
    void generateResults_emptySnapshot_doesNotUpsert() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var event = runCompletedEvent(RunOutcome.PASSED, List.of());

        resultService.generateResults(event);

        verify(testResultRepository, never()).upsert(any());
    }

    @Test
    void generateResults_runPassed_upsertsOnePassedRowPerCase() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var cases = List.of(existingCase(), existingCase(), existingCase());
        var event = runCompletedEvent(RunOutcome.PASSED, cases);

        resultService.generateResults(event);

        var captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> r.status() == ResultStatus.PASSED);
        assertThat(captor.getAllValues()).allMatch(r -> r.errorMessage() == null);
    }

    @Test
    void generateResults_runFailed_upsertsAtLeastOneFailedRow() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var cases = List.of(existingCase(), existingCase(), existingCase());
        var event = runCompletedEvent(RunOutcome.FAILED, cases);

        resultService.generateResults(event);

        var captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, times(3)).upsert(captor.capture());
        var failed = captor.getAllValues().stream().filter(r -> r.status() == ResultStatus.FAILED).toList();
        assertThat(failed).isNotEmpty();
        assertThat(failed).allMatch(r -> r.errorMessage() != null);
    }

    @Test
    void generateResults_withCaseResults_mapsVerdictDurationAndEpoch() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var c1 = UUID.randomUUID();
        var c2 = UUID.randomUUID();
        var summaries = List.of(
            new CaseResultSummary(c1, CaseResultSummary.Verdict.PASSED, 120, null, 0, List.of()),
            new CaseResultSummary(c2, CaseResultSummary.Verdict.TIMEOUT, 30000,
                "request exceeded 30000 ms", 1, List.of()));
        var event = runCompletedEventWithCaseResults(RunOutcome.FAILED,
            List.of(new TestCaseSnapshotItem(c1, "a", 0), new TestCaseSnapshotItem(c2, "b", 1)), summaries);

        resultService.generateResults(event);

        var captor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository, times(2)).upsert(captor.capture());
        var saved = captor.getAllValues();
        assertThat(saved).extracting(TestResult::testCaseId).containsExactly(c1, c2);
        assertThat(saved).extracting(TestResult::status)
            .containsExactly(ResultStatus.PASSED, ResultStatus.FAILED);
        assertThat(saved).extracting(TestResult::durationMs).containsExactly(120, 30000);
        assertThat(saved).extracting(TestResult::attemptEpoch).containsExactly(0, 1);
        assertThat(saved).extracting(TestResult::retryCount).containsExactly(0, 1);
        assertThat(saved.get(1).errorMessage()).isEqualTo("request exceeded 30000 ms");
    }

    @Test
    void recordChunk_foreignTenantRun_isNoOp() {
        when(getRunUseCase.getDomain(runId, orgId))
            .thenThrow(new RunNotFoundException("Run not found: " + runId));

        resultService.recordChunk(chunk(UUID.randomUUID(), 0, CaseResultSummary.Verdict.PASSED, List.of()));

        verify(testResultRepository, never()).upsert(any());
        verify(artifactMetadataRepository, never()).upsertForCase(any(), any(), any(), anyInt(), anyList());
    }

    @Test
    void recordChunk_staleExecutionId_isNoOp() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var event = new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId,
            UUID.randomUUID(), Instant.now(), ResultChunkEvent.SCHEMA_VERSION,
            UUID.randomUUID(), 0, CaseResultSummary.Verdict.PASSED, 10L, null, List.of());

        resultService.recordChunk(event);

        verify(testResultRepository, never()).upsert(any());
    }

    @Test
    void recordChunk_validChunk_upsertsResultAndArtifactsAtChunkEpoch() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(existingRun());
        var caseId = UUID.randomUUID();
        var refs = List.of(
            new ArtifactReference(ArtifactType.SCREENSHOT, "org/o/run/r/case/c/attempt/1/SCREENSHOT/s.png",
                "image/png", 2048L, ArtifactReference.Availability.AVAILABLE, null),
            new ArtifactReference(ArtifactType.TRACE, null, null, null,
                ArtifactReference.Availability.UNAVAILABLE, "store-unreachable"));

        resultService.recordChunk(chunk(caseId, 1, CaseResultSummary.Verdict.FAILED, refs));

        var resultCaptor = ArgumentCaptor.forClass(TestResult.class);
        verify(testResultRepository).upsert(resultCaptor.capture());
        assertThat(resultCaptor.getValue().attemptEpoch()).isEqualTo(1);
        assertThat(resultCaptor.getValue().status()).isEqualTo(ResultStatus.FAILED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestResultArtifact>> artifactCaptor = ArgumentCaptor.forClass(List.class);
        verify(artifactMetadataRepository).upsertForCase(eq(orgId), eq(runId), eq(caseId), eq(1),
            artifactCaptor.capture());
        assertThat(artifactCaptor.getValue()).hasSize(2);
        assertThat(artifactCaptor.getValue().get(0).storageKey())
            .isEqualTo("org/o/run/r/case/c/attempt/1/SCREENSHOT/s.png");
        assertThat(artifactCaptor.getValue().get(1).unavailableReason()).isEqualTo("store-unreachable");
    }

    @Test
    void getAnalytics_computesPassRateCorrectly() {
        when(getRunStatsUseCase.getStats(eq(projectId), eq(orgId), any(Instant.class)))
            .thenReturn(new RunStats(10, 7, 3));

        var result = resultService.getAnalytics(projectId, orgId, 7);

        assertThat(result.passRatePercent()).isEqualTo(70.0);
        assertThat(result.totalRuns()).isEqualTo(10);
        assertThat(result.passedRuns()).isEqualTo(7);
        assertThat(result.failedRuns()).isEqualTo(3);
    }

    @Test
    void getAnalytics_zeroRuns_returnsZeroPercentNotDivideByZero() {
        when(getRunStatsUseCase.getStats(eq(projectId), eq(orgId), any(Instant.class)))
            .thenReturn(new RunStats(0, 0, 0));

        var result = resultService.getAnalytics(projectId, orgId, 7);

        assertThat(result.passRatePercent()).isEqualTo(0.0);
    }

    @Test
    void list_delegatesToRepository() {
        var testResult = new TestResult(UUID.randomUUID(), orgId, runId, UUID.randomUUID(),
            ResultStatus.PASSED, 500, null, 0, 0, Instant.now());
        var pageResult = new PageResult<>(List.of(testResult), 1, 20, 1L);
        when(testResultRepository.findAllByRunIdAndOrgId(runId, orgId, 1, 20)).thenReturn(pageResult);

        var result = resultService.list(runId, orgId, 1, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(testResult.id());
        verify(testResultRepository).findAllByRunIdAndOrgId(runId, orgId, 1, 20);
    }

    private ResultChunkEvent chunk(UUID caseId, int epoch, CaseResultSummary.Verdict verdict,
                                   List<ArtifactReference> artifacts) {
        return new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), ResultChunkEvent.SCHEMA_VERSION, caseId, epoch, verdict, 42L, null, artifacts);
    }

    private RunCompletedEvent runCompletedEvent(RunOutcome outcome, List<TestCaseSnapshotItem> testCases) {
        return runCompletedEvent(outcome, testCases, executionId);
    }

    private RunCompletedEvent runCompletedEvent(RunOutcome outcome, List<TestCaseSnapshotItem> testCases,
                                                UUID eventExecutionId) {
        return new RunCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), orgId, runId, eventExecutionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, projectId, suiteId, outcome, testCases, null);
    }

    private RunCompletedEvent runCompletedEventWithCaseResults(RunOutcome outcome,
                                                              List<TestCaseSnapshotItem> testCases,
                                                              List<CaseResultSummary> caseResults) {
        return new RunCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, projectId, suiteId, outcome, testCases, caseResults);
    }

    private TestCaseSnapshotItem existingCase() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "Login works", 1);
    }

    private TestRun existingRun() {
        return new TestRun(runId, orgId, projectId, suiteId, UUID.randomUUID(),
            executionId, RunStatus.PASSED, UUID.randomUUID(), new RunConfigSnapshot(List.of()),
            Instant.now(), Instant.now(), Instant.now());
    }
}
