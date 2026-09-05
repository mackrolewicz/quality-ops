package com.qualityops.api.scm.exception;

/** ADR-009 §4 — the provider rejected the resolved credential (401/403) (-> 400). */
public class ScmAuthException extends RuntimeException {

    public ScmAuthException(String message) {
        super(message);
    }
}
