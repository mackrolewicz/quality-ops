package com.qualityops.api.scm.application.port.out;

import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.scm.exception.ScmAuthException;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepositoryProvider;

import java.time.Instant;

/** ADR-009 §4 — outbound SCM REST operations. One implementation per provider;
 *  the Phase-5-stable seam (SCM stays REST). All calls are bounded outbound HTTP
 *  the caller has already host-allowlisted + SSRF-guarded. */
public interface ScmPort {

    RepositoryProvider provider();

    /** "Test connection": repo reachable, credential valid, default branch. Never
     *  throws — transport / auth failures are reported through the result. */
    ScmProbeResult probe(RepositoryTarget target, String resolvedCredential);

    /** Resolve a mutable branch/tag/short-sha to a full 40-hex commit. */
    ResolvedCommit resolveRef(RepositoryTarget target, String ref, String resolvedCredential)
        throws RepositoryRefUnresolvableException, ScmAuthException;

    /** Mint a short-lived read-only checkout credential where the provider
     *  supports it (GitHub App installation token — Phase 4); otherwise return
     *  the PAT unchanged. */
    CheckoutCredential mintCheckoutCredential(RepositoryTarget target, String resolvedCredential);

    record RepositoryTarget(RepositoryProvider provider, String host, String ownerPath, String repoName) {}

    record ResolvedCommit(String sha, RepoRefType refType, Instant committedAt, String subject) {}

    record ScmProbeResult(boolean ok, String defaultBranch, String resolvedHost, long latencyMs, String error) {}

    record CheckoutCredential(String token, Instant expiresAt) {}
}
