package com.qualityops.worker.execution.exception;

/** ADR-009 §5 — the image resolved locally has a digest that differs from the
 *  digest pinned in {@code qualityops.repo-exec.images.*}. No container is run;
 *  the caller maps this to a {@code BLOCKED} case
 *  ({@code qualityops.repo.blocked{reason=digest_mismatch}}). */
public class DigestMismatchException extends RuntimeException {

    public DigestMismatchException(String imageRef, String pinnedDigest, String actualDigest) {
        super("Runner image " + imageRef + " resolved to digest " + actualDigest
            + " but the allowlist pins " + pinnedDigest);
    }
}
