package com.qualityops.api.scm.exception;

/** ADR-009 §4 — a mutable ref did not resolve to a commit at the provider
 *  (-> 422 {@code REPOSITORY_REF_UNRESOLVABLE}). */
public class RepositoryRefUnresolvableException extends RuntimeException {

    public RepositoryRefUnresolvableException(String message) {
        super(message);
    }
}
