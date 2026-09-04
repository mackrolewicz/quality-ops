package com.qualityops.api.scm.adapter.out.scm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.application.port.out.ScmPort;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.scm.exception.ScmAuthException;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepositoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** ADR-009 §4 — GitHub (cloud + Enterprise) ref→SHA + probe over the REST API. */
@Component
class GitHubScmAdapter extends AbstractHttpScmAdapter implements ScmPort {

    private static final Logger log = LoggerFactory.getLogger(GitHubScmAdapter.class);
    private static final Pattern SHA_40 = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern SHA_LIKE = Pattern.compile("[0-9a-fA-F]{7,40}");

    GitHubScmAdapter(RepoExecApiProperties props, ObjectMapper json) {
        super(props, json);
    }

    @Override
    public RepositoryProvider provider() {
        return RepositoryProvider.GITHUB;
    }

    @Override
    public ResolvedCommit resolveRef(RepositoryTarget target, String ref, String resolvedCredential)
            throws RepositoryRefUnresolvableException, ScmAuthException {
        String base = apiBase(target.host());
        String url = base + "/repos/" + target.ownerPath() + "/" + target.repoName() + "/commits/" + ref;
        AbstractHttpScmAdapter.HttpOutcome outcome;
        try {
            outcome = get(url, headers(resolvedCredential));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RepositoryRefUnresolvableException("provider unreachable resolving ref '" + ref + "'");
        } catch (IOException e) {
            throw new RepositoryRefUnresolvableException("provider unreachable resolving ref '" + ref + "'");
        }
        if (outcome.status() == 401 || outcome.status() == 403) {
            throw new ScmAuthException("GitHub rejected the credential (HTTP " + outcome.status() + ")");
        }
        if (outcome.status() != 200) {
            throw new RepositoryRefUnresolvableException(
                "GitHub could not resolve ref '" + ref + "' (HTTP " + outcome.status() + ")");
        }
        String sha = outcome.body().path("sha").asText(null);
        if (sha == null || !SHA_40.matcher(sha).matches()) {
            throw new RepositoryRefUnresolvableException("GitHub returned no commit SHA for ref '" + ref + "'");
        }
        return new ResolvedCommit(sha, refTypeFor(ref), parseInstant(
            outcome.body().path("commit").path("committer").path("date").asText(null)),
            firstLine(outcome.body().path("commit").path("message").asText(null)));
    }

    @Override
    public ScmProbeResult probe(RepositoryTarget target, String resolvedCredential) {
        String base = apiBase(target.host());
        String host = URI.create(base).getHost();
        String url = base + "/repos/" + target.ownerPath() + "/" + target.repoName();
        long start = System.nanoTime();
        try {
            AbstractHttpScmAdapter.HttpOutcome outcome = get(url, headers(resolvedCredential));
            long ms = elapsedMs(start);
            if (outcome.status() == 200) {
                return new ScmProbeResult(true, outcome.body().path("default_branch").asText(null), host, ms, null);
            }
            return new ScmProbeResult(false, null, host, ms, "HTTP " + outcome.status());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScmProbeResult(false, null, host, elapsedMs(start), "interrupted");
        } catch (IOException e) {
            log.debug("GitHub probe to {} failed: {}", host, e.toString());
            return new ScmProbeResult(false, null, host, elapsedMs(start), e.getClass().getSimpleName());
        }
    }

    @Override
    public CheckoutCredential mintCheckoutCredential(RepositoryTarget target, String resolvedCredential) {
        // Phase 4: a GitHub App installation token. For now the PAT is passed through unchanged.
        return new CheckoutCredential(resolvedCredential, null);
    }

    private String apiBase(String host) {
        String override = props.scm() == null ? null : props.scm().githubApiBase();
        if (override != null && !override.isBlank()) {
            return trimTrailingSlash(override);
        }
        return "github.com".equalsIgnoreCase(host) ? "https://api.github.com" : "https://" + host + "/api/v3";
    }

    private static Map<String, String> headers(String token) {
        var h = new HashMap<String, String>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            h.put("Authorization", "Bearer " + token);
        }
        return h;
    }

    private static RepoRefType refTypeFor(String ref) {
        return SHA_LIKE.matcher(ref).matches() ? RepoRefType.COMMIT : RepoRefType.BRANCH;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 500 ? line.substring(0, 500) : line;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
