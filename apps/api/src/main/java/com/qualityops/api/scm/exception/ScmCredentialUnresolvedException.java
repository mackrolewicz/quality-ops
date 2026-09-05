package com.qualityops.api.scm.exception;

/** ADR-009 §4 — the connection carries a {@code credentialRef} that no configured
 *  {@code ScmCredentialResolver} source can resolve (-> 400). */
public class ScmCredentialUnresolvedException extends RuntimeException {

    public ScmCredentialUnresolvedException(String credentialRef) {
        super("Unresolved repository credential reference: " + credentialRef);
    }
}
