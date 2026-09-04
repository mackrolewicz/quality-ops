package com.qualityops.api.result.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §1 — deterministic flakiness / stability scoring (pure, no Spring/DB). */
class AnalyticsFlakyScoreTest {

    @Test
    void flakiness_alternatingPassFail20_isApproximately1() {
        assertThat(AnalyticsScores.flakiness(20, 19)).isEqualTo(1.0);
    }

    @Test
    void flakiness_allPass_isZero() {
        assertThat(AnalyticsScores.flakiness(20, 0)).isEqualTo(0.0);
    }

    @Test
    void flakiness_allFail_isZero() {
        assertThat(AnalyticsScores.flakiness(20, 0)).isEqualTo(0.0);
    }

    @Test
    void flakiness_singleTransitionIn20_isApproximately005() {
        assertThat(AnalyticsScores.flakiness(20, 1)).isEqualTo(0.05);
    }

    @Test
    void stability_isOneMinusFlakiness() {
        assertThat(AnalyticsScores.stability(20, 1)).isEqualTo(0.95);
    }

    @Test
    void flakiness_singleRun_isZero() {
        assertThat(AnalyticsScores.flakiness(1, 0)).isEqualTo(0.0);
    }
}
