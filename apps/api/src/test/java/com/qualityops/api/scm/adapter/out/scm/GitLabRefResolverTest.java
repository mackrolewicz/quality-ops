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

/** ADR-009 §4 — GitLab ref→SHA resolution over MockWebServer (no Spring). */
class GitLabRefResolverTest {

    private static final String SHA = "fedcba9876543210fedcba9876543210fedcba98";

    private MockWebServer server;
    private GitLabScmAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = new GitLabScmAdapter(propsWithBase(server.url("/").toString()), new ObjectMapper());
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private static RepoExecApiProperties propsWithBase(String base) {
        var scm = new RepoExecApiProperties.Scm(List.of("gitlab.com"), true, Duration.ofSeconds(5),
            Duration.ofSeconds(10), "QUALITYOPS_SCM_CREDENTIAL_", "", "", base);
        return new RepoExecApiProperties(true, new RepoExecApiProperties.Images(
            "pw@sha256:x", "j@sha256:x", "py@sha256:x", "cy@sha256:x", "k6@sha256:x"),
            Duration.ofMinutes(10), Duration.ofMinutes(30), null, scm, false);
    }

    private static RepositoryTarget target() {
        return new RepositoryTarget(RepositoryProvider.GITLAB, "gitlab.com", "acme", "web");
    }

    @Test
    void resolveRef_tagResolves_returns40HexIdFromCommitsEndpoint() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
            "{\"id\":\"" + SHA + "\",\"committed_date\":\"2024-05-06T07:08:09.000+02:00\",\"title\":\"release 2.0\"}"));

        ResolvedCommit commit = adapter.resolveRef(target(), "v2.0", "glpat-token");

        assertThat(commit.sha()).isEqualTo(SHA);
        assertThat(commit.refType()).isEqualTo(RepoRefType.BRANCH);
        assertThat(commit.subject()).isEqualTo("release 2.0");
        assertThat(commit.committedAt()).isNotNull();
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/v4/projects/acme%2Fweb/repository/commits/v2.0");
        assertThat(request.getHeader("PRIVATE-TOKEN")).isEqualTo("glpat-token");
    }

    @Test
    void resolveRef_unknownRef_throwsRepositoryRefUnresolvable() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"404 Reference Not Found\"}"));

        assertThatThrownBy(() -> adapter.resolveRef(target(), "ghost", "glpat-token"))
            .isInstanceOf(RepositoryRefUnresolvableException.class);
    }

    @Test
    void resolveRef_providerForbidden_throwsScmAuth() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"message\":\"403 Forbidden\"}"));

        assertThatThrownBy(() -> adapter.resolveRef(target(), "main", "glpat-token"))
            .isInstanceOf(ScmAuthException.class);
    }

    @Test
    void probe_ok_returnsDefaultBranch() {
        server.enqueue(new MockResponse().setBody("{\"default_branch\":\"main\"}"));

        var result = adapter.probe(target(), "glpat-token");

        assertThat(result.ok()).isTrue();
        assertThat(result.defaultBranch()).isEqualTo("main");
    }
}
