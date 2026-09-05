package com.qualityops.api.scm.dto;

/** ADR-009 §11 — outbound "test connection" probe outcome. */
public record TestConnectionResponse(
    boolean ok,
    String defaultBranch,
    String resolvedHost,
    long latencyMs,
    String error
) {}
