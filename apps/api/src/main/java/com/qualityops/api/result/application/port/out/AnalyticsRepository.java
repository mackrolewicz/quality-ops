package com.qualityops.api.result.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only analytics port over {@code test_results} + {@code test_runs}. Every
 * method is org- and project-scoped (ADR-008 §1-2); no analytics query crosses a
 * tenant boundary.
 */
public interface AnalyticsRepository {

    /** Windowed flakiness scan: the last {@code window} PASSED/FAILED results per case,
     *  keeping only cases with {@code >= minRuns} results in the window. */
    List<FlakyRow> flakyWindow(UUID orgId, UUID projectId, int window, int minRuns);

    /** {@code test_case_id -> } most-recent result status, for the given cases in the org. */
    Map<UUID, String> lastStatusByCase(UUID orgId, UUID projectId, Collection<UUID> caseIds);

    /** {@code test_case_id -> } case name, for the given cases in the org. */
    Map<UUID, String> caseNames(UUID orgId, Collection<UUID> caseIds);

    /** Run pass/fail counts grouped by {@code test_runs.created_at} day, since {@code since}. */
    List<DayRunRow> runCountsByDay(UUID orgId, UUID projectId, Instant since);

    /** Avg / p95 case {@code duration_ms} grouped by the run's {@code created_at} day. */
    List<DayDurationRow> durationByRunDay(UUID orgId, UUID projectId, Instant since);

    /** Top-{@code limit} cases by p95 {@code duration_ms} over the window, {@code samples >= minSamples}. */
    List<SlowRow> slowByP95(UUID orgId, UUID projectId, Instant since, int limit, int minSamples);

    record FlakyRow(UUID testCaseId, int runsAnalyzed, int passCount, int transitions, Instant lastRunAt) {}

    record DayRunRow(LocalDate date, long totalRuns, long passedRuns, long failedRuns) {}

    record DayDurationRow(LocalDate date, Double avgMs, Double p95Ms) {}

    record SlowRow(UUID testCaseId, long samples, double avgMs, double p95Ms, double maxMs) {}
}
