package com.qualityops.worker.execution.domain;

/** Result of a successful PUT. {@code deduped} is true when an object with a
 *  matching {@code sha256} was already present at the key and the transfer was
 *  skipped. */
public record StoredArtifact(String storageKey, String contentType, long sizeBytes,
                             String sha256, boolean deduped) {}
