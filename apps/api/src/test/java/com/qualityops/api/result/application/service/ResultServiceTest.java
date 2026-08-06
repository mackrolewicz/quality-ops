package com.qualityops.api.result.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.domain.RunStats;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.event.RunCompletedEvent;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.result.domain.ResultStatus;
import com.qualityops.api.result.domain.TestResult;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultServiceTest {

    @Mock
    private TestResultRepository testResultRepository;

    @Mock
    private GetRunStatsUseCase getRunStatsUseCase;

    private ResultService resultService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resultService = new ResultService(testResultRepository, getRunStatsUseCase);
    }

    @Test
    void generateResults_duplicateEvent_isNoOpAndNeverSaves() {
        when(testResultRepository.existsByRunId(runId, orgId)).thenReturn(true);
        var event = runCompletedEvent(RunStatus.PASSED, List.of(existingCase()));

        resultService.generateResults(event);

        verify(testResultRepository, never()).saveAll(anyList());
    }

    @Test
    void generateResults_runPassed_allResultsPassed() {
        when(testResultRepository.existsByRunId(runId, orgId)).thenReturn(false);
        var cases = List.of(existingCase(), existingCase(), existingCase());
        var event = runCompletedEvent(RunStatus.PASSED, cases);

        resultService.generateResults(event);

        var captor = ArgumentCaptor.<List<TestResult>>captor();
        verify(testResultRepository).saveAll(captor.capture());
        var saved = captor.getValue();
        assertThat(saved).hasSize(3);
        assertThat(saved).allMatch(r -> r.status() == ResultStatus.PASSED);
        assertThat(saved).allMatch(r -> r.errorMessage() == null);
    }

    @Test
    void generateResults_runFailed_atLeastOneFailedAndRestPassed() {
        when(testResultRepository.existsByRunId(runId, orgId)).thenReturn(false);
        var cases = List.of(existingCase(), existingCase(), existingCase());
        var event = runCompletedEvent(RunStatus.FAILED, cases);

        resultService.generateResults(event);

        var captor = ArgumentCaptor.<List<TestResult>>captor();
        verify(testResultRepository).saveAll(captor.capture());
        var saved = captor.getValue();
        assertThat(saved).hasSize(3);
        var failed = saved.stream().filter(r -> r.status() == ResultStatus.FAILED).toList();
        assertThat(failed).isNotEmpty();
        assertThat(failed).allMatch(r -> r.errorMessage() != null);
    }

    @Test
    void generateResults_emptySnapshot_logsAndDoesNotSave() {
        when(testResultRepository.existsByRunId(runId, orgId)).thenReturn(false);
        var event = runCompletedEvent(RunStatus.PASSED, List.of());

        resultService.generateResults(event);

        verify(testResultRepository, never()).saveAll(anyList());
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
            ResultStatus.PASSED, 500, null, 0, Instant.now());
        var pageResult = new PageResult<>(List.of(testResult), 1, 20, 1L);
        when(testResultRepository.findAllByRunIdAndOrgId(runId, orgId, 1, 20)).thenReturn(pageResult);

        var result = resultService.list(runId, orgId, 1, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(testResult.id());
        verify(testResultRepository).findAllByRunIdAndOrgId(runId, orgId, 1, 20);
    }

    private RunCompletedEvent runCompletedEvent(RunStatus status, List<TestCaseSnapshotItem> testCases) {
        return new RunCompletedEvent(runId, orgId, projectId, suiteId, status, Instant.now(), testCases);
    }

    private TestCaseSnapshotItem existingCase() {
        return new TestCaseSnapshotItem(UUID.randomUUID(), "Login works", 1);
    }
}
