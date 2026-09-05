package com.qualityops.worker.execution.domain;

public record BrowserAssertionOutcome(com.qualityops.events.BrowserAssertion.Type type,
                                      String selectorDescription, String expected,
                                      String actual, boolean passed) {}
