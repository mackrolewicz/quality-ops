package com.qualityops.api.result.application.port.in;

import com.qualityops.events.RunCompletedEvent;

public interface GenerateResultsUseCase {

    /**
     * Generates one {@code TestResult} per test case in the completed run's
     * suite. Idempotent — safe to call more than once for the same event.
     */
    void generateResults(RunCompletedEvent event);
}
