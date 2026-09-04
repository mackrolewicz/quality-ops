package com.qualityops.api.result.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.application.port.in.RepositoryRunWriteUseCase;
import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.execution.exception.RunNotFoundException;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import com.qualityops.api.result.application.port.in.GenerateResultsUseCase;
import com.qualityops.api.result.application.port.in.GetAnalyticsUseCase;
import com.qualityops.api.result.application.port.in.ListRepositoryItemsUseCase;
import com.qualityops.api.result.application.port.in.ListResultsUseCase;
import com.qualityops.api.result.application.port.in.RecordCaseResultChunkUseCase;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.application.port.out.RepositoryTestItemRepository;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.result.domain.ArtifactAvailability;
import com.qualityops.api.result.domain.ArtifactType;
import com.qualityops.api.result.domain.RepositoryTestItem;
import com.qualityops.api.result.domain.ResultStatus;
import com.qualityops.api.result.domain.TestResult;
import com.qualityops.api.result.domain.TestResultArtifact;
import com.qualityops.api.result.dto.AnalyticsResponse;
import com.qualityops.api.result.dto.RepositoryTestItemResponse;
import com.qualityops.api.result.dto.TestResultResponse;
import com.qualityops.events.ArtifactReference;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import com.qualityops.events.TestCaseSnapshotItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class ResultService implements ListResultsUseCase, GetAnalyticsUseCase,
    GenerateResultsUseCase, RecordCaseResultChunkUseCase, ListRepositoryItemsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResultService.class);
    private static final int MIN_DURATION_MS = 100;
    private static final int MAX_DURATION_MS = 2000;
    private static final int DEFAULT_ANALYTICS_DAYS = 7;
    private static final int MIN_ANALYTICS_DAYS = 1;
    private static final int MAX_ANALYTICS_DAYS = 90;

    private final TestResultRepository testResultRepository;
    private final ArtifactMetadataRepository artifactMetadataRepository;
    private final RepositoryTestItemRepository repositoryTestItemRepository;
    private final RepositoryRunWriteUseCase repositoryRunWriteUseCase;
    private final GetRunStatsUseCase getRunStatsUseCase;
    private final GetRunUseCase getRunUseCase;
    private final RunProgressNotifier runProgressNotifier;
    private final boolean persistReportSnippets;

    public ResultService(TestResultRepository testResultRepository,
                          ArtifactMetadataRepository artifactMetadataRepository,
                          RepositoryTestItemRepository repositoryTestItemRepository,
                          RepositoryRunWriteUseCase repositoryRunWriteUseCase,
                          GetRunStatsUseCase getRunStatsUseCase,
                          GetRunUseCase getRunUseCase,
                          RunProgressNotifier runProgressNotifier,
                          RepoExecApiProperties repoExecApiProperties) {
        this.testResultRepository = testResultRepository;
        this.artifactMetadataRepository = artifactMetadataRepository;
        this.repositoryTestItemRepository = repositoryTestItemRepository;
        this.repositoryRunWriteUseCase = repositoryRunWriteUseCase;
        this.getRunStatsUseCase = getRunStatsUseCase;
        this.getRunUseCase = getRunUseCase;
        this.runProgressNotifier = runProgressNotifier;
        this.persistReportSnippets = repoExecApiProperties.persistReportSnippets();
    }

    @Override
    public void generateResults(RunCompletedEvent event) {
        if (guardRun(event.runId(), event.orgId(), event.executionId()) == null) {
            return;
        }
        // Score against the run's frozen config snapshot carried on the event —
        // never a fresh lookup; the run is immutable.
        var cases = event.testCases();
        if (cases.isEmpty()) {
            log.warn("Run {} has an empty config snapshot — nothing to generate", event.runId());
            return;
        }

        List<CaseOutcome> outcomes =
            event.caseResults() != null && !event.caseResults().isEmpty()
                ? event.caseResults().stream().map(ResultService::fromSummary).toList()
                : fabricate(event, cases);

        outcomes.forEach(o -> applyCaseOutcome(event.orgId(), event.runId(), o));

        // ADR-009 §2.5 — the v5 terminal alone reconciles repository_test_item +
        // repository_run telemetry if every results.chunk was lost.
        if (event.caseResults() != null) {
            for (CaseResultSummary cr : event.caseResults()) {
                applyRepositoryPayload(event.orgId(), event.runId(), event.executionId(),
                    cr.attemptEpoch(), cr.repositoryItems(), cr.repositoryProvenance());
            }
        }

        long passed = outcomes.stream().filter(o -> o.status() == ResultStatus.PASSED).count();
        log.info("Reconciled {} case results for run {} ({} passed, {} failed) from terminal",
            outcomes.size(), event.runId(), passed, outcomes.size() - passed);
    }

    @Override
    public void recordChunk(ResultChunkEvent event) {
        if (guardRun(event.runId(), event.orgId(), event.executionId()) == null) {
            return;
        }
        applyCaseOutcome(event.orgId(), event.runId(), new CaseOutcome(
            event.testCaseId(), mapVerdict(event.verdict()), (int) event.durationMillis(),
            event.firstFailureReason(), event.attemptEpoch(), event.artifacts()));

        applyRepositoryPayload(event.orgId(), event.runId(), event.executionId(),
            event.attemptEpoch(), event.repositoryItems(), event.repositoryProvenance());

        log.debug("Recorded result chunk for run {} case {} epoch {}",
            event.runId(), event.testCaseId(), event.attemptEpoch());

        // Best-effort per-case WebSocket push (ADR-008 §5) — never fails the
        // api-results consumer transaction.
        try {
            runProgressNotifier.publish(RunProgressEvent.caseDone(event.runId(), event.orgId(),
                event.testCaseId(), event.verdict().name(), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("WS chunk notify failed for run {} case {}", event.runId(), event.testCaseId(), e);
        }
    }

    /** Org + executionId guard shared by the chunk and terminal paths. */
    private TestRun guardRun(UUID runId, UUID orgId, UUID executionId) {
        final TestRun run;
        try {
            run = getRunUseCase.getDomain(runId, orgId);
        } catch (RunNotFoundException e) {
            log.warn("Result event for unknown or foreign-tenant run {} — skipping", runId);
            return null;
        }
        if (!run.executionId().equals(executionId)) {
            log.warn("Result event for run {} carries stale executionId {} (persisted {}) — skipping",
                runId, executionId, run.executionId());
            return null;
        }
        if (run.status() == com.qualityops.api.execution.domain.RunStatus.CANCELLED) {
            log.warn("Result event for run {} which was cancelled while QUEUED — skipping", runId);
            return null;
        }
        return run;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepositoryTestItemResponse> listRepositoryItems(UUID runId, UUID orgId) {
        return repositoryTestItemRepository.findByRunIdAndOrgId(runId, orgId).stream()
            .map(RepositoryTestItemResponse::from)
            .toList();
    }

    /** ADR-009 §7 — repository run's per-test breakdown + run-level telemetry.
     *  Both are org- + executionId-guarded and epoch-monotone; a stale event is a
     *  0-row no-op. Called from the chunk and the terminal. */
    private void applyRepositoryPayload(UUID orgId, UUID runId, UUID executionId, int attemptEpoch,
                                        List<com.qualityops.events.RepositoryTestItem> items,
                                        RepositoryRunProvenance provenance) {
        if (items != null && !items.isEmpty()) {
            repositoryTestItemRepository.upsertForRun(orgId, runId, attemptEpoch,
                toDomainItems(items, attemptEpoch));
        }
        if (provenance != null) {
            repositoryRunWriteUseCase.applyProvenance(runId, orgId, executionId, provenance, attemptEpoch);
        }
    }

    private List<RepositoryTestItem> toDomainItems(
            List<com.qualityops.events.RepositoryTestItem> items, int attemptEpoch) {
        return items.stream()
            .map(i -> RepositoryTestItem.of(i.suite(), i.name(), i.status().name(),
                i.durationMillis() < 0 ? null : (int) Math.min(i.durationMillis(), Integer.MAX_VALUE),
                i.failureType(), i.failureMessage(), attemptEpoch, persistReportSnippets))
            .toList();
    }

    /** The single idempotent, epoch-guarded, org-guarded write path. */
    private void applyCaseOutcome(UUID orgId, UUID runId, CaseOutcome o) {
        testResultRepository.upsert(new TestResult(null, orgId, runId, o.testCaseId(),
            o.status(), o.durationMs(), o.errorMessage(), o.attemptEpoch(), o.attemptEpoch(), null));

        var refs = o.artifacts() == null ? List.<ArtifactReference>of() : o.artifacts();
        var domainArtifacts = refs.stream()
            .map(a -> toDomainArtifact(orgId, runId, o.testCaseId(), o.attemptEpoch(), a))
            .toList();
        artifactMetadataRepository.upsertForCase(orgId, runId, o.testCaseId(), o.attemptEpoch(), domainArtifacts);
    }

    @Override
    public PageResult<TestResultResponse> list(UUID runId, UUID orgId, int page, int size) {
        var result = testResultRepository.findAllByRunIdAndOrgId(runId, orgId, page, size);
        return new PageResult<>(
            result.items().stream().map(TestResultResponse::from).toList(),
            result.page(),
            result.size(),
            result.total()
        );
    }

    @Override
    public AnalyticsResponse getAnalytics(UUID projectId, UUID orgId, int days) {
        int safeDays = days <= 0 ? DEFAULT_ANALYTICS_DAYS
            : Math.min(Math.max(days, MIN_ANALYTICS_DAYS), MAX_ANALYTICS_DAYS);
        var since = Instant.now().minus(safeDays, ChronoUnit.DAYS);
        var stats = getRunStatsUseCase.getStats(projectId, orgId, since);
        double passRate = stats.totalRuns() == 0 ? 0.0 : (stats.passedRuns() * 100.0 / stats.totalRuns());
        return new AnalyticsResponse(
            projectId, stats.totalRuns(), stats.passedRuns(), stats.failedRuns(), passRate, since, Instant.now());
    }

    private static TestResultArtifact toDomainArtifact(UUID orgId, UUID runId, UUID caseId, int epoch,
                                                       ArtifactReference a) {
        var status = a.status() == ArtifactReference.Availability.AVAILABLE
            ? ArtifactAvailability.AVAILABLE : ArtifactAvailability.UNAVAILABLE;
        return TestResultArtifact.inbound(orgId, runId, caseId, epoch,
            ArtifactType.valueOf(a.artifactType().name()),
            a.storageKey(), a.contentType(), a.sizeBytes(), status, a.unavailableReason());
    }

    private static CaseOutcome fromSummary(CaseResultSummary s) {
        return new CaseOutcome(s.testCaseId(), mapVerdict(s.verdict()), (int) s.durationMillis(),
            s.firstFailureReason(), s.attemptEpoch(), s.artifacts());
    }

    private static ResultStatus mapVerdict(CaseResultSummary.Verdict v) {
        return v == CaseResultSummary.Verdict.PASSED ? ResultStatus.PASSED : ResultStatus.FAILED;
    }

    // ---- legacy fabrication path (v1/v2/v3 events with null/empty caseResults) ----

    private List<CaseOutcome> fabricate(RunCompletedEvent event, List<TestCaseSnapshotItem> cases) {
        return event.outcome() == RunOutcome.PASSED
            ? cases.stream().map(c -> fabricated(c, ResultStatus.PASSED, null)).toList()
            : fabricateWithFailures(cases);
    }

    private List<CaseOutcome> fabricateWithFailures(List<TestCaseSnapshotItem> cases) {
        var shuffled = new ArrayList<>(cases);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        int failCount = 1 + ThreadLocalRandom.current().nextInt(cases.size());

        var outcomes = new ArrayList<CaseOutcome>(cases.size());
        for (int i = 0; i < shuffled.size(); i++) {
            var testCase = shuffled.get(i);
            outcomes.add(i < failCount
                ? fabricated(testCase, ResultStatus.FAILED, "Assertion failed in " + testCase.name())
                : fabricated(testCase, ResultStatus.PASSED, null));
        }
        return outcomes;
    }

    private CaseOutcome fabricated(TestCaseSnapshotItem testCase, ResultStatus status, String errorMessage) {
        int durationMs = ThreadLocalRandom.current().nextInt(MIN_DURATION_MS, MAX_DURATION_MS + 1);
        return new CaseOutcome(testCase.testCaseId(), status, durationMs, errorMessage, 0, List.of());
    }

    /** One case's reconciled outcome, from a chunk, a terminal summary, or fabrication. */
    private record CaseOutcome(UUID testCaseId, ResultStatus status, int durationMs,
                               String errorMessage, int attemptEpoch, List<ArtifactReference> artifacts) {}
}
