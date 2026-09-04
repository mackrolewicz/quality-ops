package com.qualityops.api.execution.domain;

import java.util.UUID;

/**
 * One entry in a run's frozen config snapshot. Captured at trigger time so
 * later edits to the test suite/cases never retroactively change a run.
 * <p>2F (ADR-009): gains an optional {@link RepoTestSpec} {@code repoTest}
 * (nullable ⇒ not a repository case). Convenience ctors keep every pre-2F call
 * site compiling.
 */
public record TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex,
                                   ApiRequestSpec apiRequest, BrowserTestSpec browserTest,
                                   RepoTestSpec repoTest) {

    public TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex) {
        this(testCaseId, name, orderIndex, null, null, null);
    }

    public TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex, ApiRequestSpec apiRequest) {
        this(testCaseId, name, orderIndex, apiRequest, null, null);
    }

    public TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex,
                                ApiRequestSpec apiRequest, BrowserTestSpec browserTest) {
        this(testCaseId, name, orderIndex, apiRequest, browserTest, null);
    }
}
