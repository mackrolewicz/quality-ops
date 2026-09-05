package com.qualityops.worker.execution.domain;

import com.qualityops.events.ApiAssertion;

public record AssertionOutcome(ApiAssertion.Type type, String target,
                               String expected, String actual, boolean passed) {}
