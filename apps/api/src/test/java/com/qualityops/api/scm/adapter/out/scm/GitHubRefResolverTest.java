package com.qualityops.api.scm.adapter.out.scm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.application.port.out.ScmPort.RepositoryTarget;
import com.qualityops.api.scm.application.port.out.ScmPort.ResolvedCommit;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.scm.exception.ScmAuthException;
import com.qualityops.events.RepoRefType;
import com.qualityops.events.RepositoryProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-009 §4 — GitHub ref→SHA resolution over MockWebServer (no Spring). */
class GitHubRefResolverTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private MockWebServer server;
    private GitHubScmAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = new GitHubScmAdapter(propsWithBase(server.url("/").toString()), new ObjectMapper());
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static RepoExecApiProperties propsWithBase(String base) {
        var scm = new RepoExecApiProperties.Scm(List.of("github.com"), true, Duration.ofSeconds(5),
            Duration.ofSeconds(10), "QUALITYOPS_SCM_CREDENTIAL_", "", base, "");
        return new RepoExecApiProperties(true, new RepoExecApiProperties.Images(
            "pw@sha256:x", "j@sha256:x", "py@sha256:x", "cy@sha256:x", "k6@sha256:x"),
            Duration.ofMinutes(10), Duration.ofMinutes(30), null, scm, false);
    }

    private static RepositoryTarget target() {
        return new RepositoryTarget(RepositoryProvider.GITHUB, "github.com", "acme", "web");
    }

    @Test
    void resolveRef_branchResolves_returns40HexShaAndBranchRefType() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
            "{\"sha\":\"" + SHA + "\",\"commit\":{\"committer\":{\"date\":\"2024-01-02T03:04:05Z\"},"
                + "\"message\":\"fix the thing\\nmore detail\"}}"));

        ResolvedCommit commit = adapter.resolveRef(target(), "main", "ghp_token");

        assertThat(commit.sha()).isEqualTo(SHA);
        assertThat(commit.refType()).isEqualTo(RepoRefType.BRANCH);
        assertThat(commit.subject()).isEqualTo("fix the thing");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/repos/acme/web/commits/main");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer ghp_token");
    }

    @Test
    void resolveRef_shaLikeRef_returnsCommitRefType() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"sha\":\"" + SHA + "\"}"));

        ResolvedCommit commit = adapter.resolveRef(target(), SHA, null);

        assertThat(commit.refType()).isEqualTo(RepoRefType.COMMIT);
    }

    @Test
    void resolveRef_unknownRef_throwsRepositoryRefUnresolvable() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"No commit found\"}"));

        assertThatThrownBy(() -> adapter.resolveRef(target(), "nope", null))
            .isInstanceOf(RepositoryRefUnresolvableException.class);
    }

    @Test
    void resolveRef_providerUnauthorized_throwsScmAuth() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"Bad credentials\"}"));

        assertThatThrownBy(() -> adapter.resolveRef(target(), "main", "bad"))
            .isInstanceOf(ScmAuthException.class);
    }

    @Test
    void probe_ok_returnsDefaultBranch() {
        server.enqueue(new MockResponse().setBody("{\"default_branch\":\"trunk\"}"));

        var result = adapter.probe(target(), "ghp_token");

        assertThat(result.ok()).isTrue();
        assertThat(result.defaultBranch()).isEqualTo("trunk");
    }

    @Test
    void probe_notFound_returnsNotOkWithError() {
        server.enqueue(new MockResponse().setResponseCode(404));

        var result = adapter.probe(target(), "ghp_token");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("404");
    }
}
