package com.qualityops.api.execution.event;

import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published once a run reaches a terminal outcome. {@code status} is always
 * PASSED or FAILED here. {@code testCases} carries the run's frozen config
 * snapshot (as captured at trigger time) so consumers — e.g. result
 * generation — act on what was actually run, not on the suite's current
 * (possibly since-edited) test case list. Per domain rule: test runs are
 * immutable, so nothing downstream may re-derive this from live suite state.
 */
public record RunCompletedEvent(
    UUID runId,
    UUID orgId,
    UUID projectId,
    UUID suiteId,
    RunStatus status,
    Instant completedAt,
    List<TestCaseSnapshotItem> testCases
) {}
