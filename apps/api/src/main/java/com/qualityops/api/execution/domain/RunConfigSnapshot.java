package com.qualityops.api.execution.domain;

import java.util.List;

public record RunConfigSnapshot(List<TestCaseSnapshotItem> testCases) {}
