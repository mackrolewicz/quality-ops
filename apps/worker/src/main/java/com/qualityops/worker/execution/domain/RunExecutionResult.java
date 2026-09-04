package com.qualityops.worker.execution.domain;

import com.qualityops.events.RunOutcome;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public record RunExecutionResult(UUID runId, UUID executionId, RunOutcome outcome,
                                 List<CaseExecutionResult> cases, Duration totalDuration) {}
