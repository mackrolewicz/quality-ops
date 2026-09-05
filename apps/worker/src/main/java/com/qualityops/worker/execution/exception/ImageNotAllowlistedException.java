package com.qualityops.worker.execution.exception;

/** ADR-009 §5 — the requested {@code imageRef} is not byte-equal to any value in
 *  {@code qualityops.repo-exec.images.*}. No container is created; the caller maps
 *  this to a {@code BLOCKED} case ({@code qualityops.repo.blocked{reason=image_not_allowlisted}}). */
public class ImageNotAllowlistedException extends RuntimeException {

    public ImageNotAllowlistedException(String imageRef) {
        super("Runner image is not on the digest-pinned allowlist: " + imageRef);
    }
}
