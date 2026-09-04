package com.qualityops.worker.execution.exception;

/** A durable artifact store operation failed. Caught by
 *  {@code ArtifactUploadService}, which degrades the artifact to
 *  {@code UNAVAILABLE} — it is NEVER allowed to fail or delay a terminal event. */
public class ArtifactStoreException extends Exception {

    public ArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public ArtifactStoreException(String message) {
        super(message);
    }
}
