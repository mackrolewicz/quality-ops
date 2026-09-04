package com.qualityops.api.scm.exception;

/** ADR-009 §4 — the connection host is not on
 *  {@code qualityops.repo-exec.scm.allowed-hosts} (-> 400). No socket is opened. */
public class RepositoryHostNotAllowedException extends RuntimeException {

    public RepositoryHostNotAllowedException(String host) {
        super("Repository host is not allowed: " + host);
    }
}
