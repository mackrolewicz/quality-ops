package com.qualityops.api.result.exception;

import com.qualityops.api.common.NotFoundException;

/** A runId is unknown, or not in the caller's org (never distinguished). */
public class ArtifactRunNotFoundException extends NotFoundException {
    public ArtifactRunNotFoundException() {
        super("ARTIFACT_RUN_NOT_FOUND", "Run not found");
    }
}
