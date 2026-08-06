package com.qualityops.api.result.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.application.port.in.GetRunStatsUseCase;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.event.RunCompletedEvent;
import com.qualityops.api.result.application.port.in.GenerateResultsUseCase;
import com.qualityops.api.result.application.port.in.GetAnalyticsUseCase;
import com.qualityops.api.result.application.port.in.ListResultsUseCase;
import com.qualityops.api.result.application.port.out.TestResultRepository;
import com.qualityops.api.result.domain.ResultStatus;
import com.qualityops.api.result.domain.TestResult;
import com.qualityops.api.result.dto.AnalyticsResponse;
import com.qualityops.api.result.dto.TestResultResponse;
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
public class ResultService implements ListResultsUseCase, GetAnalyticsUseCase, GenerateResultsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResultService.class);
    private static final int MIN_DURATION_MS = 100;
    private static final int MAX_DURATION_MS = 2000;
    private static final int DEFAULT_ANALYTICS_DAYS = 7;
    private static final int MIN_ANALYTICS_DAYS = 1;
    private static final int MAX_ANALYTICS_DAYS = 90;

    private final TestResultRepository testResultRepository;
    private final GetRunStatsUseCase getRunStatsUseCase;

    public ResultService(TestResultRepository testResultRepository,
                          GetRunStatsUseCase getRunStatsUseCase) {
        this.testResultRepository = testResultRepository;
        this.getRunStatsUseCase = getRunStatsUseCase;
    }

    @Override
    public void generateResults(RunCompletedEvent event) {
        if (testResultRepository.existsByRunId(event.runId(), event.orgId())) {
            log.warn("Results already generated for run {} — skipping duplicate delivery", event.runId());
            return;
        }

        // Use the run's frozen config snapshot carried on the event, never a
        // fresh lookup of the suite's current test cases — the run is
        // immutable and must be scored against what it actually ran.
        var cases = event.testCases();
        if (cases.isEmpty()) {
            log.warn("Run {} has an empty config snapshot — nothing to generate", event.runId());
            return;
        }

        var results = event.status() == RunStatus.PASSED
            ? buildAllPassed(event, cases)
            : buildWithFailures(event, cases);

        testResultRepository.saveAll(results);

        long passedCount = results.stream().filter(r -> r.status() == ResultStatus.PASSED).count();
        long failedCount = results.stream().filter(r -> r.status() == ResultStatus.FAILED).count();
        log.info("Generated {} results for run {} ({} passed, {} failed)",
            results.size(), event.runId(), passedCount, failedCount);
    }

    @Override
    public PageResult<TestResultResponse> list(UUID runId, UUID orgId, int page, int size) {
        // No cross-check against execution's run ownership here: test_results
        // rows already carry the correct orgId set at generation time, and the
        // repository query filters by it. A runId from another org simply
        // yields an empty page rather than a 404 — keeps this module's
        // dependency graph free of a GetRunUseCase coupling.
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

    private List<TestResult> buildAllPassed(RunCompletedEvent event, List<TestCaseSnapshotItem> cases) {
        return cases.stream()
            .map(testCase -> newResult(event, testCase, ResultStatus.PASSED, null))
            .toList();
    }

    private List<TestResult> buildWithFailures(RunCompletedEvent event, List<TestCaseSnapshotItem> cases) {
        var shuffled = new ArrayList<>(cases);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        int failCount = 1 + ThreadLocalRandom.current().nextInt(cases.size());

        var results = new ArrayList<TestResult>(cases.size());
        for (int i = 0; i < shuffled.size(); i++) {
            var testCase = shuffled.get(i);
            if (i < failCount) {
                results.add(newResult(event, testCase, ResultStatus.FAILED, "Assertion failed in " + testCase.name()));
            } else {
                results.add(newResult(event, testCase, ResultStatus.PASSED, null));
            }
        }
        return results;
    }

    private TestResult newResult(RunCompletedEvent event, TestCaseSnapshotItem testCase, ResultStatus status, String errorMessage) {
        int durationMs = ThreadLocalRandom.current().nextInt(MIN_DURATION_MS, MAX_DURATION_MS + 1);
        return new TestResult(
            UUID.randomUUID(),
            event.orgId(),
            event.runId(),
            testCase.testCaseId(),
            status,
            durationMs,
            errorMessage,
            0,
            Instant.now()
        );
    }
}
