package com.qualityops.worker.execution.exception;

/** A framework report ({@code JUNIT_XML} / {@code K6_SUMMARY_JSON}) could not be
 *  parsed — malformed XML/JSON, or none of {@code reportPaths} resolved to a
 *  file. The runner maps this to case {@code ERROR} with a safe reason; the run
 *  is NOT aborted (ADR-009 §7). */
public class ReportParseException extends RuntimeException {

    public ReportParseException(String message) {
        super(message);
    }

    public ReportParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
