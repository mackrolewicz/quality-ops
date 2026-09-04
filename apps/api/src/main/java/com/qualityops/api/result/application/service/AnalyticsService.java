package com.qualityops.api.result.application.service;

import com.qualityops.api.config.AnalyticsProperties;
import com.qualityops.api.config.QueueMetrics;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.result.application.port.in.GetDurationTrendsUseCase;
import com.qualityops.api.result.application.port.in.GetFlakyAnalyticsUseCase;
import com.qualityops.api.result.application.port.in.GetSlowTestsUseCase;
import com.qualityops.api.result.application.port.out.AnalyticsRepository;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.DayDurationRow;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.DayRunRow;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.FlakyRow;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.SlowRow;
import com.qualityops.api.result.dto.DurationTrendsResponse;
import com.qualityops.api.result.dto.FlakyAnalyticsResponse;
import com.qualityops.api.result.dto.FlakyTestRow;
import com.qualityops.api.result.dto.SlowTestRow;
import com.qualityops.api.result.dto.SlowTestsResponse;
import com.qualityops.api.result.dto.TrendPoint;
import io.micrometer.core.instrument.Timer;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * On-the-fly analytics (ADR-008 §1-2): flaky detection, duration trends and slowest
 * tests, each backed by native aggregate queries over {@code test_results} + {@code test_runs}.
 * No materialised state.
 *
 * <p><b>Cache note.</b> The three read methods are {@code @Cacheable} (30 s, Redis).
 * The {@code projectId}-ownership check ({@link GetProjectUseCase#getDomain}) runs in the
 * method body, so it is skipped on a cache hit. That is safe: every cache key is
 * {@code orgId}-prefixed ({@code analytics.flaky::{orgId}:{projectId}:{window}}), so a
 * cross-tenant read is impossible, and a cross-project read inside one org is already
 * permitted for these roles. The cold-cache miss still enforces the 404.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService implements GetFlakyAnalyticsUseCase, GetDurationTrendsUseCase,
    GetSlowTestsUseCase {

    private static final int WINDOW_MIN = 5;
    private static final int WINDOW_MAX = 50;
    private static final int DAYS_MIN = 1;
    private static final int DAYS_MAX = 90;
    private static final int LIMIT_MIN = 1;
    private static final int LIMIT_MAX = 100;
    private static final int SLOW_MIN_SAMPLES = 3;
    private static final int DEFAULT_DAYS = 7;

    private final AnalyticsRepository repo;
    private final GetProjectUseCase getProjectUseCase;
    private final AnalyticsProperties props;
    private final QueueMetrics metrics;

    public AnalyticsService(AnalyticsRepository repo,
                            GetProjectUseCase getProjectUseCase,
                            AnalyticsProperties props,
                            QueueMetrics metrics) {
        this.repo = repo;
        this.getProjectUseCase = getProjectUseCase;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    @Cacheable(cacheNames = "analytics.flaky", key = "#orgId + ':' + #projectId + ':' + #window")
    public FlakyAnalyticsResponse getFlaky(UUID projectId, UUID orgId, int window) {
        getProjectUseCase.getDomain(projectId, orgId);
        int w = clamp(window <= 0 ? props.flaky().windowSize() : window, WINDOW_MIN, WINDOW_MAX);
        Timer.Sample sample = Timer.start();
        try {
            List<FlakyRow> rows = repo.flakyWindow(orgId, projectId, w, props.flaky().minRuns());
            List<UUID> caseIds = rows.stream().map(FlakyRow::testCaseId).toList();
            Map<UUID, String> names = repo.caseNames(orgId, caseIds);
            Map<UUID, String> lastStatus = repo.lastStatusByCase(orgId, projectId, caseIds);
            List<FlakyTestRow> tests = rows.stream()
                .map(r -> new FlakyTestRow(
                    r.testCaseId(),
                    names.get(r.testCaseId()),
                    r.runsAnalyzed(),
                    r.passCount(),
                    r.transitions(),
                    AnalyticsScores.flakiness(r.runsAnalyzed(), r.transitions()),
                    AnalyticsScores.stability(r.runsAnalyzed(), r.transitions()),
                    lastStatus.get(r.testCaseId()),
                    r.lastRunAt()))
                .toList();
            return new FlakyAnalyticsResponse(projectId, w, tests);
        } finally {
            sample.stop(metrics.analyticsQuery("flaky"));
        }
    }

    @Override
    @Cacheable(cacheNames = "analytics.trends", key = "#orgId + ':' + #projectId + ':' + #days")
    public DurationTrendsResponse getTrends(UUID projectId, UUID orgId, int days) {
        getProjectUseCase.getDomain(projectId, orgId);
        int d = clamp(days <= 0 ? DEFAULT_DAYS : days, DAYS_MIN, props.trends().maxDays());
        Timer.Sample sample = Timer.start();
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate start = today.minusDays(d - 1L);
            Instant since = start.atStartOfDay(ZoneOffset.UTC).toInstant();

            Map<LocalDate, DayRunRow> runsByDay = repo.runCountsByDay(orgId, projectId, since).stream()
                .collect(Collectors.toMap(DayRunRow::date, r -> r));
            Map<LocalDate, DayDurationRow> durationByDay = repo.durationByRunDay(orgId, projectId, since).stream()
                .collect(Collectors.toMap(DayDurationRow::date, r -> r));

            List<TrendPoint> points = new ArrayList<>(d);
            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                DayRunRow rc = runsByDay.get(day);
                DayDurationRow dc = durationByDay.get(day);
                points.add(new TrendPoint(
                    day,
                    rc == null ? 0L : rc.totalRuns(),
                    rc == null ? 0L : rc.passedRuns(),
                    rc == null ? 0L : rc.failedRuns(),
                    dc == null || dc.avgMs() == null ? 0.0 : round2(dc.avgMs()),
                    dc == null || dc.p95Ms() == null ? 0.0 : round2(dc.p95Ms())));
            }
            return new DurationTrendsResponse(projectId, d, points);
        } finally {
            sample.stop(metrics.analyticsQuery("trends"));
        }
    }

    @Override
    @Cacheable(cacheNames = "analytics.slow", key = "#orgId + ':' + #projectId + ':' + #days + ':' + #limit")
    public SlowTestsResponse getSlow(UUID projectId, UUID orgId, int days, int limit) {
        getProjectUseCase.getDomain(projectId, orgId);
        int d = clamp(days <= 0 ? DEFAULT_DAYS : days, DAYS_MIN, DAYS_MAX);
        int lim = clamp(limit <= 0 ? props.slow().defaultLimit() : limit, LIMIT_MIN, LIMIT_MAX);
        Timer.Sample sample = Timer.start();
        try {
            Instant since = LocalDate.now(ZoneOffset.UTC).minusDays(d - 1L)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
            List<SlowRow> rows = repo.slowByP95(orgId, projectId, since, lim, SLOW_MIN_SAMPLES);
            Map<UUID, String> names = repo.caseNames(orgId,
                rows.stream().map(SlowRow::testCaseId).toList());
            List<SlowTestRow> tests = rows.stream()
                .map(r -> new SlowTestRow(
                    r.testCaseId(),
                    names.get(r.testCaseId()),
                    r.samples(),
                    round2(r.avgMs()),
                    round2(r.p95Ms()),
                    round2(r.maxMs())))
                .toList();
            return new SlowTestsResponse(projectId, d, lim, tests);
        } finally {
            sample.stop(metrics.analyticsQuery("slow"));
        }
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.min(Math.max(value, lo), hi);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
