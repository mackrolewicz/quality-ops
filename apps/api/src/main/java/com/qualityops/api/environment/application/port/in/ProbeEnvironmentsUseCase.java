package com.qualityops.api.environment.application.port.in;

/**
 * ADR-008 §3 — one pass of the environment-health probe. {@code sweep()} is public
 * and driven directly by ITs (mirrors {@code StuckRunReaperService.sweep()}); the
 * ShedLock-locked {@code EnvironmentHealthProbeJob} is the production trigger.
 */
public interface ProbeEnvironmentsUseCase {

    void sweep();
}
