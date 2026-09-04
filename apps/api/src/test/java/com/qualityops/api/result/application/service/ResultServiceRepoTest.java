package com.qualityops.api.result.application.service;

import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.application.port.out.RepositoryTestItemRepository;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.result.domain.RepositoryTestItem;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.RepositoryTestItem.RepoItemStatus;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-009 §7 — ResultService applies the repository per-test breakdown and the
 *  run-level provenance from both the chunk and the v5 terminal. */
@ExtendWith(MockitoExtension.class)
class ResultServiceRepoTest {

    @Mock private TestResultRepository testResultRepository;
    @Mock private ArtifactMetadataRepository artifactMetadataRepository;
    @Mock private RepositoryTestItemRepository repositoryTestItemRepository;
    @Mock private RepositoryRunWriteUseCase repositoryRunWriteUseCase;
    @Mock private GetRunStatsUseCase getRunStatsUseCase;
    @Mock private GetRunUseCase getRunUseCase;
    @Mock private RunProgressNotifier runProgressNotifier;
    @Mock private RepoExecApiProperties repoExecApiProperties;

    private ResultService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ResultService(testResultRepository, artifactMetadataRepository,
            repositoryTestItemRepository, repositoryRunWriteUseCase, getRunStatsUseCase, getRunUseCase,
            runProgressNotifier, repoExecApiProperties);
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(run());
    }

    private TestRun run() {
        return new TestRun(runId, orgId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            executionId, RunStatus.RUNNING, UUID.randomUUID(), new RunConfigSnapshot(List.of()),
            Instant.now(), null, Instant.now());
    }

    private com.qualityops.events.RepositoryTestItem wireItem(String name, RepoItemStatus status) {
        return new com.qualityops.events.RepositoryTestItem("suite.A", name, status, 12L,
            status == RepoItemStatus.FAILED ? "AssertionError" : null,
            status == RepoItemStatus.FAILED ? "expected 1 got 2" : null);
    }

    private RepositoryRunProvenance provenance() {
        return new RepositoryRunProvenance("sha256:abc", 1, 3, 2, 1, 0,
            Instant.now().minusSeconds(30), Instant.now().minusSeconds(25), Instant.now());
    }

    private ResultChunkEvent chunk(List<com.qualityops.events.RepositoryTestItem> items,
                                   RepositoryRunProvenance prov) {
        return new ResultChunkEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), ResultChunkEvent.SCHEMA_VERSION, caseId, 1, CaseResultSummary.Verdict.FAILED,
            42L, "3 of 3 tests, 1 failed", List.of(), items, prov);
    }

    @Test
    void recordChunk_withRepositoryItems_upsertsThemAtTheChunkEpoch() {
        service.recordChunk(chunk(List.of(wireItem("t1", RepoItemStatus.PASSED),
            wireItem("t2", RepoItemStatus.FAILED)), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RepositoryTestItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(repositoryTestItemRepository).upsertForRun(eq(orgId), eq(runId), eq(1), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allSatisfy(i -> assertThat(i.attemptEpoch()).isEqualTo(1));
    }

    @Test
    void recordChunk_persistReportSnippetsFalse_dropsFailureMessageKeepsType() {
        service.recordChunk(chunk(List.of(wireItem("t2", RepoItemStatus.FAILED)), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RepositoryTestItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(repositoryTestItemRepository).upsertForRun(any(), any(), eq(1), captor.capture());
        var item = captor.getValue().get(0);
        assertThat(item.failureMessage()).isNull();
        assertThat(item.failureType()).isEqualTo("AssertionError");
    }

    @Test
    void recordChunk_withProvenance_appliesTelemetry() {
        var prov = provenance();
        service.recordChunk(chunk(List.of(), prov));

        verify(repositoryRunWriteUseCase).applyProvenance(runId, orgId, executionId, prov, 1,
            "3 of 3 tests, 1 failed");
    }

    @Test
    void recordChunk_nonRepoCase_touchesNeitherRepositoryWrite() {
        service.recordChunk(chunk(List.of(), null));

        verify(repositoryTestItemRepository, never()).upsertForRun(any(), any(), anyInt(), any());
        verify(repositoryRunWriteUseCase, never()).applyProvenance(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void generateResults_terminalCarriesRepositoryPayload_reconcilesItemsAndProvenance() {
        var prov = provenance();
        var cr = new CaseResultSummary(caseId, CaseResultSummary.Verdict.FAILED, 42L, "1 failed", 2,
            List.of(), List.of(wireItem("t1", RepoItemStatus.PASSED), wireItem("t2", RepoItemStatus.FAILED)),
            prov);
        var terminal = new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunCompletedEvent.SCHEMA_VERSION, UUID.randomUUID(), UUID.randomUUID(),
            RunOutcome.FAILED,
            List.of(new com.qualityops.events.TestCaseSnapshotItem(caseId, "repo case", 0)), List.of(cr));

        service.generateResults(terminal);

        verify(repositoryTestItemRepository).upsertForRun(eq(orgId), eq(runId), eq(2), any());
        verify(repositoryRunWriteUseCase).applyProvenance(runId, orgId, executionId, prov, 2, "1 failed");
    }
}
