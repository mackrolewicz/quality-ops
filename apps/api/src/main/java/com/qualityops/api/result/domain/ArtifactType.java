package com.qualityops.api.result.domain;

/** Kind of durable artifact a case produced. Module-local, String-typed
 *  (mirrors {@code com.qualityops.events.ArtifactType}) to keep the result
 *  module free of a transport dependency. */
public enum ArtifactType {
    SCREENSHOT, TRACE, HAR, CONSOLE_LOG, HTTP_EXCHANGE, REPORT
}
