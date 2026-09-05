package com.qualityops.events;

/** Reference to one artifact a case produced. Never carries bytes, a presigned
 *  URL, or a raw snippet. {@code storageKey} is null exactly when
 *  {@code status == UNAVAILABLE}; {@code unavailableReason} is a redaction-safe
 *  category ({@code store-unreachable}, {@code timeout}, {@code too-large},
 *  {@code store-disabled}, {@code suppressed-secret-case}). */
public record ArtifactReference(
        ArtifactType artifactType,
        String storageKey,            // nullable ⇒ status == UNAVAILABLE
        String contentType,           // nullable when unavailable
        Long sizeBytes,               // nullable when unavailable
        Availability status,          // AVAILABLE | UNAVAILABLE
        String unavailableReason      // nullable; category only, redaction-safe
) {
    public enum Availability { AVAILABLE, UNAVAILABLE }
}
