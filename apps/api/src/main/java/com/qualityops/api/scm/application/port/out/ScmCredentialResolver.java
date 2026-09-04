package com.qualityops.api.scm.application.port.out;

/** ADR-009 §4 — resolves a connection's opaque {@code credentialRef} to a
 *  provider token, used only for preflight ref-resolution and the "test
 *  connection" probe. The plaintext is never stored on any row, event, snapshot,
 *  log line, or artifact. */
public interface ScmCredentialResolver {

    /** @param credentialRef opaque key ({@code [A-Z0-9_]{1,64}}) or null for a
     *                       public repo.
     *  @return the plaintext token, or null when {@code credentialRef} is null or
     *          no configured source holds it. */
    String resolve(String credentialRef);
}
