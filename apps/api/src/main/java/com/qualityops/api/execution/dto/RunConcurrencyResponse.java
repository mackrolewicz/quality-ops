package com.qualityops.api.execution.dto;

/** ADR-007 §4. {@code source ∈ {"OVERRIDE", "DEFAULT"}}. */
public record RunConcurrencyResponse(int maxActiveRuns, String source) {}
