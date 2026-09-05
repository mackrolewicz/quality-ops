package com.qualityops.events;

import java.util.UUID;

/** One entry in a run's frozen execution snapshot, carried on the wire so the
 *  Worker never reads a database.
 *  <p>v5 (ADR-009): gains an optional {@link RepoTestSnapshot} {@code repoTest};
 *  wire-compatible with v1–v4 (missing field deserialises as null). */
public record TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex,
                                   ApiRequestSnapshot apiRequest /* nullable ⇒ not an API case */,
                                   BrowserTestSnapshot browserTest /* nullable ⇒ not a browser case */,
                                   RepoTestSnapshot repoTest /* nullable ⇒ not a repository case */) {

    /** v1 convenience — simulated case. Keeps every 3-arg call site compiling. */
    public TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex) {
        this(testCaseId, name, orderIndex, null, null, null);
    }

    /** v2 convenience — API case. Keeps every 4-arg call site compiling. */
    public TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex, ApiRequestSnapshot apiRequest) {
        this(testCaseId, name, orderIndex, apiRequest, null, null);
    }

    /** v3/v4 convenience — API and/or browser case. Keeps every 5-arg call site compiling. */
    public TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex,
                                ApiRequestSnapshot apiRequest, BrowserTestSnapshot browserTest) {
        this(testCaseId, name, orderIndex, apiRequest, browserTest, null);
    }
}
