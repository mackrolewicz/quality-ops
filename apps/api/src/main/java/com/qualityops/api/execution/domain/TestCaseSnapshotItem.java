package com.qualityops.api.execution.domain;

import java.util.UUID;

/**
 * One entry in a run's frozen config snapshot. Captured at trigger time so
 * later edits to the test suite/cases never retroactively change a run.
 */
public record TestCaseSnapshotItem(UUID testCaseId, String name, int orderIndex) {}
