package com.qualityops.worker.execution.domain;

public record BrowserStepOutcome(int index, com.qualityops.events.BrowserStep.Action action,
                                 String selectorDescription, BrowserStepStatus status,
                                 long durationMillis, String failureReason) {}
