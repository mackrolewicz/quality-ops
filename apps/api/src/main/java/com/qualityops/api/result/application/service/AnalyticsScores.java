package com.qualityops.api.result.application.service;

/**
 * Deterministic flakiness / stability scoring (ADR-008 §1). Pure arithmetic — no
 * Spring, no DB — so it is unit-tested in isolation.
 *
 * <p>{@code flakiness = runs <= 1 ? 0.0 : round2(transitions / (runs - 1.0))} — {@code 0.0}
 * for all-pass or all-fail, {@code 1.0} for perfect alternation. {@code stability = 1 - flakiness}.
 */
public final class AnalyticsScores {

    private AnalyticsScores() {
    }

    public static double flakiness(int runs, int transitions) {
        if (runs <= 1) {
            return 0.0;
        }
        return round2(transitions / (runs - 1.0));
    }

    public static double stability(int runs, int transitions) {
        return round2(1.0 - flakiness(runs, transitions));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
