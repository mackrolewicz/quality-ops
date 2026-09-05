package com.qualityops.api.result.exception;

import com.qualityops.api.common.NotFoundException;

/** An artifact id is unknown, or belongs to another org (never distinguished). */
public class ArtifactNotFoundException extends NotFoundException {
    public ArtifactNotFoundException() {
        super("ARTIFACT_NOT_FOUND", "Artifact not found");
    }
}
