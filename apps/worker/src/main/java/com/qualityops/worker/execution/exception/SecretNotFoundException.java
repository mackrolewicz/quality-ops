package com.qualityops.worker.execution.exception;

/** Thrown when a {@code secretRef} cannot be resolved at execution time. The
 *  runner maps this to a {@code BLOCKED} case (deterministic config problem —
 *  never retried). The message carries only the author-chosen key name. */
public class SecretNotFoundException extends RuntimeException {

    private final String key;

    public SecretNotFoundException(String key) {
        super("unresolved secret reference: " + key);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
