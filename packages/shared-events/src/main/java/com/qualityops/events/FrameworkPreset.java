package com.qualityops.events;

/** Test framework a repository run executes under. Selects the digest-pinned
 *  runner image and the report format the Worker expects. */
public enum FrameworkPreset { PLAYWRIGHT, JUNIT, PYTEST, CYPRESS, K6 }
