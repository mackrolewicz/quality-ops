package com.qualityops.events;

/** Kind of durable artifact a case can produce. 2B3 emits only
 *  {@link #SCREENSHOT} and {@link #TRACE}; the rest are reserved so later
 *  increments add them without an event or port change. */
public enum ArtifactType {
    SCREENSHOT, TRACE, HAR, CONSOLE_LOG, HTTP_EXCHANGE, REPORT
}
