package com.qualityops.worker.execution.domain;

import java.nio.file.Path;

/** A staged local file plus its precomputed integrity metadata, ready to PUT. */
public record ArtifactUpload(ArtifactRef ref, Path source, String contentType, long sizeBytes, String sha256) {}
